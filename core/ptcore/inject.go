package ptcore

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"

	"go.yaml.in/yaml/v3"
)

// tsProxyName 是注入给 mihomo 的 tailnet 节点名；用户配置只按名字引用它，例如 `dialer-proxy: ts`。
const tsProxyName = "ts"

// viaKey 是 Petrel 自己的节点字段：标注节点前面有一个 mihomo 看不见的中转（例如服务商的国内入口把端口转发到海外），
// 只用于在链路里显示，不改变连接方式。injectConfig 读出后从交给 mihomo 的配置里删掉。
const viaKey = "petrel-via"

// builtinProxyNames 是 mihomo 内置的出站名，中转标注不能和它们重名。
var builtinProxyNames = []string{"DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL"}

// extraControllerKeys 是 external-controller 之外的其余 controller 入口与相关设置，注入时一律删除。
var extraControllerKeys = []string{
	"external-controller-tls",
	"external-controller-unix",
	"external-controller-pipe",
	"external-controller-cors",
}

// InjectConfig 把用户的 mihomo 配置改写成能在本 App 的 VpnService 里运行的形态：
// TUN 改为接管 VpnService 交来的 fd，去掉桌面端的网卡绑定，并注入指向 tsnet Loopback SOCKS5 的 ts 节点。
// 用户配置没写 secret（缺失或空串）时写入 fallbackSecret，写了就保留用户的值。其余内容原样保留。
func InjectConfig(user []byte, tunFd int, socksAddr string, socksPass string, fallbackSecret string) ([]byte, error) {
	out, _, _, err := injectConfig(user, tunFd, socksAddr, socksPass, fallbackSecret)
	return out, err
}

// ConfigIPv6 读配置顶层的 ipv6（缺省时同 mihomo 取 true），供 Kotlin 建 VpnService 时决定接不接管 IPv6。
// 配置关了 IPv6 时 mihomo 拨不出 IPv6，TUN 却照收：gvisor 先替 App 完成握手、mihomo 再拨号失败，
// App 看到的是「连上又断」而不是「不可达」，不会退回 IPv4（微信的公众号文章就卡在这里）。
// 读不了或解析不了时返回 true，保持接管，由随后的 Start 报真正的错误。
func ConfigIPv6(configPath string) bool {
	user, err := os.ReadFile(configPath)
	if err != nil {
		return true
	}
	return configIPv6(user)
}

func configIPv6(user []byte) bool {
	var top struct {
		IPv6 *bool `yaml:"ipv6"`
	}
	if err := yaml.Unmarshal(user, &top); err != nil || top.IPv6 == nil {
		return true
	}
	return *top.IPv6
}

// injectConfig 同 InjectConfig，另外返回警告与中转标注（节点名 → petrel-via 的值）。
func injectConfig(user []byte, tunFd int, socksAddr string, socksPass string, fallbackSecret string) ([]byte, []string, map[string]string, error) {
	var cfg map[string]any
	if err := yaml.Unmarshal(user, &cfg); err != nil {
		return nil, nil, nil, fmt.Errorf("parse config: %w", err)
	}
	if cfg == nil {
		cfg = map[string]any{}
	}

	host, portStr, err := net.SplitHostPort(socksAddr)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("socks addr %q: %w", socksAddr, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("socks port %q: %w", portStr, err)
	}

	var warnings []string

	// gvisor 而不是 system：VpnService 把本 App 自身排除在外（防自环），
	// system / mixed stack 靠内核 NAT，回包会绕过 tun 而断链。
	cfg["tun"] = map[string]any{
		"enable":                true,
		"file-descriptor":       tunFd,
		"stack":                 "gvisor",
		"auto-route":            false,
		"auto-detect-interface": false,
		"dns-hijack":            []any{"any:53"},
		"mtu":                   1400,
	}

	delete(cfg, "interface-name")
	delete(cfg, "routing-mark")
	if _, ok := cfg["auto-detect-interface"]; ok {
		cfg["auto-detect-interface"] = false
	}
	cfg["external-controller"] = "127.0.0.1:9090"
	// REST 只开在 127.0.0.1:9090。桌面派生配置里的 TLS / unix / pipe 入口与跨域设置一律删掉：
	// TLS controller 会在手机的局域网地址上监听，违背 allow-lan: false。（tls: 段本身不动，别的 listener 可能还要用）
	for _, k := range extraControllerKeys {
		if _, ok := cfg[k]; ok {
			delete(cfg, k)
			warnings = append(warnings, fmt.Sprintf("removed %s: the REST controller only listens on 127.0.0.1:9090", k))
		}
	}
	cfg["allow-lan"] = false
	// mihomo 的 REST 固定开在 127.0.0.1:9090，手机上任何 App 都连得到：没有 secret 就用本次随机生成的
	if v, present := cfg["secret"]; !present || v == nil || v == "" {
		cfg["secret"] = fallbackSecret
	}

	if dns, ok := cfg["dns"].(map[string]any); ok {
		delete(dns, "listen")
	}
	// listeners / tunnels 的入站不受 allow-lan 约束、默认监听 0.0.0.0：桌面派生配置里的入站会把代理开放给同一局域网。
	// 入站保留，监听地址一律改成 127.0.0.1，只本机可连。
	warnings = append(warnings, loopbackInbounds(cfg)...)

	tsProxy := map[string]any{
		"name":     tsProxyName,
		"type":     "socks5",
		"server":   host,
		"port":     port,
		"username": "tsnet",
		"password": socksPass,
		"udp":      true,
	}

	var proxies []any
	if raw, ok := cfg["proxies"]; ok && raw != nil {
		list, ok := raw.([]any)
		if !ok {
			return nil, nil, nil, fmt.Errorf("proxies: expected a list, got %T", raw)
		}
		proxies = list
	}

	vias, err := takeVias(proxies, groupNamesIn(cfg))
	if err != nil {
		return nil, nil, nil, err
	}

	replaced := false
	for i, p := range proxies {
		m, ok := p.(map[string]any)
		if !ok {
			continue
		}
		// 节点级的网卡绑定与 routing-mark 同顶层一样是桌面端设置：安卓上 SO_MARK 需要 CAP_NET_ADMIN，带着它每次拨号都 EPERM
		delete(m, "interface-name")
		delete(m, "routing-mark")
		if m["name"] == tsProxyName {
			warnings = append(warnings, fmt.Sprintf("config already has a proxy named %q; replaced by the built-in tailnet node", tsProxyName))
			proxies[i] = tsProxy
			replaced = true
		}
	}
	if !replaced {
		proxies = append(proxies, tsProxy)
	}
	cfg["proxies"] = proxies

	out, err := yaml.Marshal(cfg)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("marshal config: %w", err)
	}
	return out, warnings, vias, nil
}

// loopbackHost 是入站被改写后的监听地址。
const loopbackHost = "127.0.0.1"

// loopbackInbounds 把 listeners 每项的 listen 与 tunnels 每项的本地地址改成 127.0.0.1，返回每处改写的警告。
// tunnels 有两种写法：字符串 "tcp/udp,<本地地址>,<目标>,<代理>"，或带 address 字段的 map。格式认不出的条目原样留给 mihomo 报错。
func loopbackInbounds(cfg map[string]any) []string {
	var warnings []string
	if list, ok := cfg["listeners"].([]any); ok {
		for i, l := range list {
			m, ok := l.(map[string]any)
			if !ok {
				continue
			}
			if v, _ := m["listen"].(string); v != loopbackHost {
				m["listen"] = loopbackHost
				warnings = append(warnings, fmt.Sprintf("listener %d: listen set to %s", i, loopbackHost))
			}
		}
	}
	if list, ok := cfg["tunnels"].([]any); ok {
		for i, t := range list {
			switch v := t.(type) {
			case string:
				parts := strings.Split(v, ",")
				if len(parts) < 2 {
					continue
				}
				if addr, changed := loopbackAddr(strings.TrimSpace(parts[1])); changed {
					parts[1] = addr
					list[i] = strings.Join(parts, ",")
					warnings = append(warnings, fmt.Sprintf("tunnel %d: local address set to %s", i, addr))
				}
			case map[string]any:
				a, _ := v["address"].(string)
				if addr, changed := loopbackAddr(a); changed {
					v["address"] = addr
					warnings = append(warnings, fmt.Sprintf("tunnel %d: local address set to %s", i, addr))
				}
			}
		}
	}
	return warnings
}

// loopbackAddr 把 host:port 的 host 换成 127.0.0.1；解析不了的原样返回、不算改写。
func loopbackAddr(addr string) (string, bool) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil || host == loopbackHost {
		return addr, false
	}
	return net.JoinHostPort(loopbackHost, port), true
}

// groupNamesIn 返回配置里 proxy-groups 的组名（格式不对的条目跳过，交给 mihomo 报错）。
func groupNamesIn(cfg map[string]any) []string {
	list, _ := cfg["proxy-groups"].([]any)
	var names []string
	for _, g := range list {
		if m, ok := g.(map[string]any); ok {
			if n, ok := m["name"].(string); ok {
				names = append(names, n)
			}
		}
	}
	return names
}

// takeVias 从各节点读出并删掉 petrel-via，返回节点名 → 中转名。
// 值必须是非空字符串，且不能和任何节点、组、ts 或 mihomo 内置出站重名：中转只是显示用的名字，
// 撞名的话界面会把它当成那个真实节点。
func takeVias(proxies []any, groups []string) (map[string]string, error) {
	taken := map[string]bool{tsProxyName: true}
	for _, n := range builtinProxyNames {
		taken[n] = true
	}
	for _, n := range groups {
		taken[n] = true
	}
	for _, p := range proxies {
		if m, ok := p.(map[string]any); ok {
			if n, ok := m["name"].(string); ok {
				taken[n] = true
			}
		}
	}

	vias := map[string]string{}
	for _, p := range proxies {
		m, ok := p.(map[string]any)
		if !ok {
			continue
		}
		raw, present := m[viaKey]
		if !present {
			continue
		}
		delete(m, viaKey)
		name, _ := m["name"].(string)
		if name == tsProxyName {
			continue // 用户写的 ts 会被内置节点替换，它的标注一并作废
		}
		via, ok := raw.(string)
		via = strings.TrimSpace(via)
		if !ok || via == "" {
			return nil, fmt.Errorf("proxy %q: %s must be a non-empty string", name, viaKey)
		}
		if taken[via] {
			return nil, fmt.Errorf("proxy %q: %s %q clashes with a proxy or group name", name, viaKey, via)
		}
		vias[name] = via
	}
	return vias, nil
}
