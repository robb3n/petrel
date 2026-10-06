package ptcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	"github.com/metacubex/mihomo/component/proxydialer"
	"github.com/metacubex/mihomo/tunnel"
)

// ExitIPURL 查出口 IP 与归属地：ip-api.com 免费接口，只有 http；字段里的 hosting / mobile 给出「机房 / 移动网络」。
// 中文地名用 lang=zh-CN。Kotlin 侧直连查询（仅 tailnet 模式）用同一个地址，解析也在 Kotlin 侧做一次。
const ExitIPURL = "http://ip-api.com/json/?lang=zh-CN&fields=status,message,country,countryCode,regionName,city,isp,as,mobile,hosting,query"

const (
	exitIPTimeout  = 8 * time.Second
	exitIPMaxBytes = 4 << 10
)

// ExitIP 经节点 node 查出口 IP，返回 ip-api 的原始 JSON（Kotlin 解析）。
// 直接经该节点拨号，不切换当前选中，所以节点页可以给没用过的节点补查。经 ts 的节点在 ModeProxy 下立即失败。
// 结果里有出口 IP，属于连接信息：不写进日志。
func ExitIP(node string) (string, error) {
	if !running() {
		return "", errors.New("not running")
	}
	p, ok := tunnel.Proxies()[node]
	if !ok {
		return "", fmt.Errorf("node %q not found", node)
	}
	d := proxydialer.New(p, false) // 与 mihomo 处理 dialer-proxy 时同一条路径；域名交给节点远端解析
	client := &http.Client{
		Timeout: exitIPTimeout,
		Transport: &http.Transport{
			Proxy:             nil,
			DisableKeepAlives: true,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return d.DialContext(ctx, network, addr)
			},
		},
	}
	resp, err := client.Get(ExitIPURL)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("http %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, exitIPMaxBytes))
	if err != nil {
		return "", err
	}
	return string(body), nil
}
