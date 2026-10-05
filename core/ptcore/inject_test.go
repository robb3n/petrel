package ptcore

import (
	"reflect"
	"strings"
	"testing"

	"go.yaml.in/yaml/v3"
)

func inject(t *testing.T, src string) (map[string]any, []string) {
	t.Helper()
	out, warnings, _, err := injectConfig([]byte(src), 42, "127.0.0.1:41234", "secret-pass", "fallback-secret")
	if err != nil {
		t.Fatalf("injectConfig: %v", err)
	}
	var cfg map[string]any
	if err := yaml.Unmarshal(out, &cfg); err != nil {
		t.Fatalf("re-parse output: %v\n%s", err, out)
	}
	return cfg, warnings
}

func findProxy(t *testing.T, cfg map[string]any, name string) map[string]any {
	t.Helper()
	list, _ := cfg["proxies"].([]any)
	for _, p := range list {
		if m, ok := p.(map[string]any); ok && m["name"] == name {
			return m
		}
	}
	t.Fatalf("proxy %q not found in %v", name, list)
	return nil
}

const desktopConfig = `
mixed-port: 10808
allow-lan: true
interface-name: en0
routing-mark: 6666
auto-detect-interface: true
external-controller: 0.0.0.0:9090
secret: keep-me
tun:
  enable: true
  stack: system
  device: utun9
  auto-route: true
  strict-route: true
dns:
  enable: true
  listen: 127.0.0.1:53
  enhanced-mode: fake-ip
proxies:
  - name: front
    type: ss
    server: 100.64.0.9
    port: 24443
    cipher: aes-256-gcm
    password: x
    interface-name: utun5
  - name: landing
    type: socks5
    server: 203.0.113.7
    port: 1080
    dialer-proxy: front
rules:
  - IP-CIDR,100.64.0.0/10,ts,no-resolve
  - MATCH,landing
`

func TestTunIsReplaced(t *testing.T) {
	cfg, _ := inject(t, desktopConfig)
	tun, ok := cfg["tun"].(map[string]any)
	if !ok {
		t.Fatalf("tun missing: %v", cfg["tun"])
	}
	want := map[string]any{
		"enable":                true,
		"file-descriptor":       42,
		"stack":                 "gvisor",
		"auto-route":            false,
		"auto-detect-interface": false,
		"mtu":                   1400,
	}
	for k, v := range want {
		if tun[k] != v {
			t.Errorf("tun.%s = %v, want %v", k, tun[k], v)
		}
	}
	if hj, _ := tun["dns-hijack"].([]any); len(hj) != 1 || hj[0] != "any:53" {
		t.Errorf("tun.dns-hijack = %v", tun["dns-hijack"])
	}
	for _, k := range []string{"device", "strict-route"} {
		if _, ok := tun[k]; ok {
			t.Errorf("tun.%s should be dropped", k)
		}
	}
}

func TestDesktopBindingsAreRemoved(t *testing.T) {
	cfg, _ := inject(t, desktopConfig)
	for _, k := range []string{"interface-name", "routing-mark"} {
		if _, ok := cfg[k]; ok {
			t.Errorf("top-level %s should be removed", k)
		}
	}
	if cfg["auto-detect-interface"] != false {
		t.Errorf("auto-detect-interface = %v, want false", cfg["auto-detect-interface"])
	}
	if cfg["allow-lan"] != false {
		t.Errorf("allow-lan = %v, want false", cfg["allow-lan"])
	}
	if cfg["external-controller"] != "127.0.0.1:9090" {
		t.Errorf("external-controller = %v", cfg["external-controller"])
	}
	if cfg["secret"] != "keep-me" {
		t.Errorf("secret should be kept, got %v", cfg["secret"])
	}
	dns := cfg["dns"].(map[string]any)
	if _, ok := dns["listen"]; ok {
		t.Errorf("dns.listen should be removed")
	}
	if dns["enhanced-mode"] != "fake-ip" {
		t.Errorf("dns.enhanced-mode should be kept, got %v", dns["enhanced-mode"])
	}
	if _, ok := findProxy(t, cfg, "front")["interface-name"]; ok {
		t.Errorf("proxy interface-name should be removed")
	}
	if findProxy(t, cfg, "landing")["dialer-proxy"] != "front" {
		t.Errorf("other proxy fields should be kept")
	}
	if rules, _ := cfg["rules"].([]any); len(rules) != 2 {
		t.Errorf("rules should be kept as-is, got %v", cfg["rules"])
	}
}

func TestTsProxyIsAppended(t *testing.T) {
	cfg, warnings := inject(t, desktopConfig)
	if len(warnings) != 0 {
		t.Errorf("unexpected warnings: %v", warnings)
	}
	ts := findProxy(t, cfg, "ts")
	want := map[string]any{
		"type":     "socks5",
		"server":   "127.0.0.1",
		"port":     41234,
		"username": "tsnet",
		"password": "secret-pass",
		"udp":      true,
	}
	for k, v := range want {
		if ts[k] != v {
			t.Errorf("ts.%s = %v, want %v", k, ts[k], v)
		}
	}
	if n := len(cfg["proxies"].([]any)); n != 3 {
		t.Errorf("proxies count = %d, want 3", n)
	}
}

func TestExistingTsProxyIsReplaced(t *testing.T) {
	src := `
proxies:
  - name: ts
    type: tailscale
    auth-key: tskey-should-vanish
  - name: other
    type: direct
`
	cfg, warnings := inject(t, src)
	if len(warnings) != 1 || !strings.Contains(warnings[0], `"ts"`) {
		t.Errorf("want one warning about ts, got %v", warnings)
	}
	ts := findProxy(t, cfg, "ts")
	if ts["type"] != "socks5" {
		t.Errorf("ts.type = %v, want socks5", ts["type"])
	}
	if _, ok := ts["auth-key"]; ok {
		t.Errorf("old ts fields should not survive")
	}
	if n := len(cfg["proxies"].([]any)); n != 2 {
		t.Errorf("proxies count = %d, want 2", n)
	}
}

func TestNoProxiesSection(t *testing.T) {
	cfg, _ := inject(t, "mode: rule\n")
	list, _ := cfg["proxies"].([]any)
	if len(list) != 1 {
		t.Fatalf("want only ts, got %v", list)
	}
	findProxy(t, cfg, "ts")
	if cfg["mode"] != "rule" {
		t.Errorf("mode should be kept")
	}
}

func TestEmptyConfig(t *testing.T) {
	cfg, _ := inject(t, "")
	findProxy(t, cfg, "ts")
	if _, ok := cfg["tun"]; !ok {
		t.Errorf("tun should be injected into an empty config")
	}
}

func TestInvalidYAML(t *testing.T) {
	if _, err := InjectConfig([]byte("proxies: [unterminated"), 1, "127.0.0.1:1", "p", "s"); err == nil {
		t.Fatal("want error for invalid YAML")
	}
}

func TestProxiesNotAList(t *testing.T) {
	if _, err := InjectConfig([]byte("proxies: {a: 1}\n"), 1, "127.0.0.1:1", "p", "s"); err == nil {
		t.Fatal("want error when proxies is not a list")
	}
}

func TestBadSocksAddr(t *testing.T) {
	if _, err := InjectConfig([]byte(""), 1, "no-port", "p", "s"); err == nil {
		t.Fatal("want error for socks addr without port")
	}
}

func TestSecretFallback(t *testing.T) {
	cases := []struct {
		name string
		src  string
		want string
	}{
		{"missing", "mixed-port: 1\n", "fallback-secret"},
		{"empty", "secret: \"\"\n", "fallback-secret"},
		{"null", "secret:\n", "fallback-secret"},
		{"present", "secret: keep-me\n", "keep-me"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			cfg, _ := inject(t, c.src)
			if cfg["secret"] != c.want {
				t.Errorf("secret = %v, want %q", cfg["secret"], c.want)
			}
		})
	}
}

func TestExtraControllerEntriesAreRemoved(t *testing.T) {
	cfg, warnings := inject(t, `
external-controller: 0.0.0.0:9090
external-controller-tls: 0.0.0.0:9443
external-controller-unix: /tmp/mihomo.sock
external-controller-pipe: \\.\pipe\mihomo
external-controller-cors:
  allow-origins:
    - "*"
tls:
  certificate: a.crt
  private-key: a.key
`)
	for _, k := range []string{"external-controller-tls", "external-controller-unix", "external-controller-pipe", "external-controller-cors"} {
		if _, ok := cfg[k]; ok {
			t.Errorf("%s should be removed", k)
		}
	}
	if cfg["external-controller"] != "127.0.0.1:9090" {
		t.Errorf("external-controller = %v", cfg["external-controller"])
	}
	if _, ok := cfg["tls"]; !ok {
		t.Error("tls section itself must be kept")
	}
	if len(warnings) != 4 {
		t.Errorf("want one warning per removed key, got %v", warnings)
	}
	for _, w := range warnings {
		if strings.Contains(w, "mihomo.sock") || strings.Contains(w, "9443") {
			t.Errorf("warning must not carry config values: %q", w)
		}
	}
}

func TestNoControllerWarningsWhenAbsent(t *testing.T) {
	_, warnings := inject(t, desktopConfig)
	if len(warnings) != 0 {
		t.Errorf("unexpected warnings: %v", warnings)
	}
}

const viaConfig = `
proxies:
  - name: tokyo
    type: socks5
    server: relay.example
    port: 8001
    petrel-via: " 服务商中转 "
  - name: plain
    type: socks5
    server: 192.0.2.1
    port: 1080
proxy-groups:
  - name: PROXY
    type: select
    proxies: [tokyo, plain]
`

func TestViaIsReadAndStripped(t *testing.T) {
	out, _, vias, err := injectConfig([]byte(viaConfig), 42, "127.0.0.1:41234", "p", "s")
	if err != nil {
		t.Fatalf("injectConfig: %v", err)
	}
	if want := map[string]string{"tokyo": "服务商中转"}; !reflect.DeepEqual(vias, want) {
		t.Errorf("vias = %v, want %v", vias, want)
	}
	if strings.Contains(string(out), viaKey) {
		t.Errorf("%s must not reach mihomo:\n%s", viaKey, out)
	}
}

func TestViaRejected(t *testing.T) {
	cases := map[string]string{
		"空串":    `petrel-via: ""`,
		"不是字符串": `petrel-via: [a, b]`,
		"和节点重名": `petrel-via: plain`,
		"和组重名":  `petrel-via: PROXY`,
		"和内置重名": `petrel-via: DIRECT`,
		"叫 ts":  `petrel-via: ts`,
	}
	for name, line := range cases {
		src := strings.Replace(viaConfig, `petrel-via: " 服务商中转 "`, line, 1)
		if _, _, _, err := injectConfig([]byte(src), 42, "127.0.0.1:41234", "p", "s"); err == nil {
			t.Errorf("%s：应当报错", name)
		}
	}
}

func TestViaOnTsIsDropped(t *testing.T) {
	src := strings.Replace(viaConfig, "proxy-groups:", `  - name: ts
    type: socks5
    server: 127.0.0.1
    port: 1
    petrel-via: whatever
proxy-groups:`, 1)
	_, _, vias, err := injectConfig([]byte(src), 42, "127.0.0.1:41234", "p", "s")
	if err != nil {
		t.Fatalf("injectConfig: %v", err)
	}
	if _, ok := vias[tsProxyName]; ok {
		t.Errorf("via on the replaced ts node must be dropped, got %v", vias)
	}
}

func TestInjectBindsInboundsToLoopback(t *testing.T) {
	cfg, warnings := inject(t, `
listeners:
  - name: mixed-in
    type: mixed
    port: 7891
  - name: socks-local
    type: socks
    port: 7892
    listen: 127.0.0.1
tunnels:
  - tcp/udp,0.0.0.0:6553,114.114.114.114:53,proxy
  - network: [tcp, udp]
    address: 0.0.0.0:7777
    target: target.com
    proxy: proxy
  - network: [tcp]
    address: 127.0.0.1:7778
    target: other.com
proxies: []
`)
	listeners, _ := cfg["listeners"].([]any)
	for i, l := range listeners {
		if got := l.(map[string]any)["listen"]; got != "127.0.0.1" {
			t.Errorf("listener %d listen = %v, want 127.0.0.1", i, got)
		}
	}
	tunnels, _ := cfg["tunnels"].([]any)
	if got := tunnels[0]; got != "tcp/udp,127.0.0.1:6553,114.114.114.114:53,proxy" {
		t.Errorf("string tunnel = %v", got)
	}
	if got := tunnels[1].(map[string]any)["address"]; got != "127.0.0.1:7777" {
		t.Errorf("map tunnel address = %v", got)
	}
	if got := tunnels[2].(map[string]any)["address"]; got != "127.0.0.1:7778" {
		t.Errorf("already-loopback tunnel address = %v", got)
	}
	// 只有真正改写过的三处（listener 0、tunnel 0、tunnel 1）出警告，且警告里不带配置里的名字
	n := 0
	for _, w := range warnings {
		if strings.Contains(w, "listener") || strings.Contains(w, "tunnel") {
			n++
			if strings.Contains(w, "mixed-in") {
				t.Errorf("warning leaks config content: %q", w)
			}
		}
	}
	if n != 3 {
		t.Errorf("got %d inbound warnings, want 3: %v", n, warnings)
	}
}

func TestInjectDropsProxyRoutingMark(t *testing.T) {
	cfg, _ := inject(t, `
proxies:
  - name: front
    type: ss
    server: 1.2.3.4
    port: 443
    cipher: aes-256-gcm
    password: x
    routing-mark: 6666
    interface-name: en0
`)
	p := findProxy(t, cfg, "front")
	for _, k := range []string{"routing-mark", "interface-name"} {
		if _, ok := p[k]; ok {
			t.Errorf("proxy still has %s", k)
		}
	}
}

func TestConfigIPv6(t *testing.T) {
	cases := []struct {
		name string
		src  string
		want bool
	}{
		{"absent defaults to mihomo's true", "mixed-port: 7890\n", true},
		{"explicit false", "ipv6: false\nmode: rule\n", false},
		{"explicit true", "ipv6: true\n", true},
		{"dns.ipv6 is not the top-level switch", "dns:\n  ipv6: false\n", true},
		{"unparsable keeps the takeover", "ipv6: [\n", true},
		{"empty", "", true},
	}
	for _, c := range cases {
		if got := configIPv6([]byte(c.src)); got != c.want {
			t.Errorf("%s: configIPv6 = %v, want %v", c.name, got, c.want)
		}
	}
	if !ConfigIPv6("/nonexistent/config.yaml") {
		t.Error("ConfigIPv6 on a missing file should keep the takeover (true)")
	}
}
