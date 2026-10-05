package ptcore

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/tailscale/ipn"
	"github.com/metacubex/tailscale/ipn/ipnstate"
)

// 对端到本机的路径种类，Kotlin 据此显示「直连 / 中继 / 空闲 / 离线」。
const (
	pathOffline   = "offline"
	pathDirect    = "direct"
	pathPeerRelay = "peer-relay"
	pathRelay     = "relay"
	pathIdle      = "idle"
)

type tailnetSelf struct {
	Name string   `json:"name"`
	IPs  []string `json:"ips"`
}

type tailnetPeer struct {
	Name     string   `json:"name"`
	IPs      []string `json:"ips"`
	OS       string   `json:"os"`
	Online   bool     `json:"online"`
	Path     string   `json:"path"`
	Relay    string   `json:"relay"`
	LastSeen string   `json:"lastSeen"`
}

type tailnetStatus struct {
	Self  tailnetSelf   `json:"self"`
	Peers []tailnetPeer `json:"peers"`
}

// peerName 取 MagicDNS 机器名（DNSName 的第一段），DNSName 为空时退回 HostName。
func peerName(p *ipnstate.PeerStatus) string {
	if dns := strings.TrimSuffix(p.DNSName, "."); dns != "" {
		name, _, _ := strings.Cut(dns, ".")
		return name
	}
	return p.HostName
}

func addrStrings(p *ipnstate.PeerStatus) []string {
	ips := make([]string, 0, len(p.TailscaleIPs))
	for _, ip := range p.TailscaleIPs {
		ips = append(ips, ip.String())
	}
	return ips
}

// peerPath 按固定顺序判定路径：离线 → 直连 → 经节点中继 → 经 DERP 中继 → 空闲。
// 空闲指在线但最近没有流量，tailscale 还没建立路径，此时说不准是直连还是中继。
func peerPath(p *ipnstate.PeerStatus) (path, relay string) {
	switch {
	case !p.Online:
		return pathOffline, ""
	case p.CurAddr != "":
		return pathDirect, ""
	case p.PeerRelay != "":
		return pathPeerRelay, ""
	case p.Active && p.Relay != "":
		return pathRelay, p.Relay
	default:
		return pathIdle, ""
	}
}

// buildTailnetStatus 把 tsnet 的 Status 转成界面用的形状；没有 Self（还没登录）时返回 false。
// hostname 是本机在 tailnet 里的节点名，Self 没带名字时用它。纯函数，不碰锁与 tsnet。
func buildTailnetStatus(st *ipnstate.Status, hostname string) (tailnetStatus, bool) {
	if st == nil || st.Self == nil {
		return tailnetStatus{}, false
	}
	self := tailnetSelf{Name: peerName(st.Self), IPs: addrStrings(st.Self)}
	if self.Name == "" {
		self.Name = hostname
	}
	if len(self.IPs) == 0 {
		for _, ip := range st.TailscaleIPs {
			self.IPs = append(self.IPs, ip.String())
		}
	}

	peers := make([]tailnetPeer, 0, len(st.Peer))
	for _, p := range st.Peer {
		path, relay := peerPath(p)
		lastSeen := ""
		if !p.Online && !p.LastSeen.IsZero() {
			lastSeen = p.LastSeen.UTC().Format(time.RFC3339)
		}
		peers = append(peers, tailnetPeer{
			Name:     peerName(p),
			IPs:      addrStrings(p),
			OS:       p.OS,
			Online:   p.Online,
			Path:     path,
			Relay:    relay,
			LastSeen: lastSeen,
		})
	}
	// 在线的在前，同组内按名字字母序；名字相同时按首个地址定序，让输出稳定
	sort.Slice(peers, func(i, j int) bool {
		a, b := peers[i], peers[j]
		if a.Online != b.Online {
			return a.Online
		}
		if a.Name != b.Name {
			return a.Name < b.Name
		}
		return strings.Join(a.IPs, ",") < strings.Join(b.IPs, ",")
	})
	return tailnetStatus{Self: self, Peers: peers}, true
}

// TailnetStatus 返回本机与对端节点的状态 JSON；VPN 未运行或 tsnet 未就绪时返回 "{}"。
func TailnetStatus() string {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return "{}"
	}
	lc, err := s.LocalClient()
	if err != nil {
		return "{}"
	}
	ctx, c := context.WithTimeout(context.Background(), 10*time.Second)
	defer c()
	st, err := lc.Status(ctx)
	if err != nil {
		logf("warn", "tailnet status: %v", err)
		return "{}"
	}
	out, ok := buildTailnetStatus(st, s.Hostname)
	if !ok {
		return "{}"
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// Logout 登出 tailnet（节点密钥失效）。之后状态回到 NeedsLogin，登录链接照常经 OnState 推出。
func Logout() error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return errors.New("not running")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, c := context.WithTimeout(context.Background(), 10*time.Second)
	defer c()
	return lc.Logout(ctx)
}

// loginGate 决定 watchTailnet 何时自动 StartLogin：进入 NeedsLogin 且还没有登录链接时触发一次，
// 回到 Running 就复位——否则登出后再进 NeedsLogin 不会再请求链接。
// StartLogin 失败且不再重试时由 [loginGate.reset] 复位。observe 在 watch 循环里调，reset 在重试 goroutine 里调，所以带锁。
type loginGate struct {
	mu        sync.Mutex
	requested bool
}

func (g *loginGate) observe(state, loginURL string) (start bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	switch {
	case state == ipn.Running.String():
		g.requested = false
	case state == ipn.NeedsLogin.String() && loginURL == "" && !g.requested:
		g.requested = true
		return true
	}
	return false
}

func (g *loginGate) reset() {
	g.mu.Lock()
	g.requested = false
	g.mu.Unlock()
}

// loginBackoff 是第 attempt 次（从 0 起）失败之后等多久再试：2s、4s、8s……上限 30s。
func loginBackoff(attempt int) time.Duration {
	d := 2 * time.Second
	for i := 0; i < attempt && d < 30*time.Second; i++ {
		d *= 2
	}
	return min(d, 30*time.Second)
}

// sleepCtx 睡 d；ctx 先结束返回 false。
func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}

// retryLogin 反复调 start 直到成功。失败后按 backoff 等待再试；等待结束时 needed() 为 false（已登录、链接已经到了）
// 就放弃并调 giveUp，让 loginGate 复位。ctx 结束（Stop）时直接返回，不复位。每次失败都回调 onErr。
func retryLogin(
	ctx context.Context,
	start func() error,
	sleep func(context.Context, time.Duration) bool,
	needed func() bool,
	backoff func(attempt int) time.Duration,
	giveUp func(),
	onErr func(error),
) {
	for attempt := 0; ; attempt++ {
		err := start()
		if err == nil {
			return
		}
		onErr(err)
		if !sleep(ctx, backoff(attempt)) {
			return
		}
		if !needed() {
			giveUp()
			return
		}
	}
}
