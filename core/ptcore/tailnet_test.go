package ptcore

import (
	"context"
	"encoding/json"
	"errors"
	"net/netip"
	"reflect"
	"testing"
	"time"

	"github.com/metacubex/tailscale/ipn"
	"github.com/metacubex/tailscale/ipn/ipnstate"
	"github.com/metacubex/tailscale/types/key"
)

func ips(s ...string) []netip.Addr {
	out := make([]netip.Addr, 0, len(s))
	for _, v := range s {
		out = append(out, netip.MustParseAddr(v))
	}
	return out
}

func TestPeerPath(t *testing.T) {
	cases := []struct {
		name      string
		p         ipnstate.PeerStatus
		wantPath  string
		wantRelay string
	}{
		{"离线优先于一切", ipnstate.PeerStatus{Online: false, CurAddr: "1.2.3.4:5", Active: true, Relay: "hkg"}, "offline", ""},
		{"直连", ipnstate.PeerStatus{Online: true, CurAddr: "1.2.3.4:5", PeerRelay: "x", Relay: "hkg", Active: true}, "direct", ""},
		{"节点中继", ipnstate.PeerStatus{Online: true, PeerRelay: "1.2.3.4:5:7", Relay: "hkg", Active: true}, "peer-relay", ""},
		{"DERP 中继", ipnstate.PeerStatus{Online: true, Relay: "hkg", Active: true}, "relay", "hkg"},
		{"有 DERP 区域但不活跃是空闲", ipnstate.PeerStatus{Online: true, Relay: "hkg", Active: false}, "idle", ""},
		{"活跃但没有任何路径是空闲", ipnstate.PeerStatus{Online: true, Active: true}, "idle", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			path, relay := peerPath(&c.p)
			if path != c.wantPath || relay != c.wantRelay {
				t.Fatalf("got (%q, %q), want (%q, %q)", path, relay, c.wantPath, c.wantRelay)
			}
		})
	}
}

func TestPeerName(t *testing.T) {
	cases := []struct {
		dns, host, want string
	}{
		{"hu.tail1234.ts.net.", "HU-box", "hu"},
		{"hu.tail1234.ts.net", "", "hu"},
		{"solo.", "x", "solo"},
		{"", "fallback-host", "fallback-host"},
	}
	for _, c := range cases {
		got := peerName(&ipnstate.PeerStatus{DNSName: c.dns, HostName: c.host})
		if got != c.want {
			t.Errorf("peerName(%q,%q) = %q, want %q", c.dns, c.host, got, c.want)
		}
	}
}

func TestBuildTailnetStatus(t *testing.T) {
	seen := time.Date(2026, 10, 1, 8, 30, 0, 0, time.FixedZone("x", 8*3600))
	st := &ipnstate.Status{
		TailscaleIPs: ips("100.64.0.20"),
		Self: &ipnstate.PeerStatus{
			DNSName:      "op12-petrel.tail1234.ts.net.",
			TailscaleIPs: ips("100.64.0.20", "fd7a:115c:a1e0::20"),
		},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			key.NewNode().Public(): {DNSName: "mba.t.ts.net.", TailscaleIPs: ips("100.64.0.13"), OS: "macOS", Online: true, CurAddr: "1.1.1.1:1"},
			key.NewNode().Public(): {DNSName: "op12.t.ts.net.", TailscaleIPs: ips("100.64.0.9"), Online: false, LastSeen: seen},
			key.NewNode().Public(): {DNSName: "hmm.t.ts.net.", TailscaleIPs: ips("100.64.0.15"), Online: true, Active: true, Relay: "hkg", LastSeen: seen},
			key.NewNode().Public(): {DNSName: "hu.t.ts.net.", TailscaleIPs: ips("100.64.0.11"), OS: "linux", Online: true, CurAddr: "2.2.2.2:2"},
			key.NewNode().Public(): {HostName: "idle-box", TailscaleIPs: ips("100.64.0.30"), Online: true},
			key.NewNode().Public(): {DNSName: "viaa.t.ts.net.", TailscaleIPs: ips("100.64.0.31"), Online: true, PeerRelay: "3.3.3.3:3:1"},
		},
	}
	got, ok := buildTailnetStatus(st, "pjd110-petrel")
	if !ok {
		t.Fatal("expected ok")
	}

	if got.Self.Name != "op12-petrel" || !reflect.DeepEqual(got.Self.IPs, []string{"100.64.0.20", "fd7a:115c:a1e0::20"}) {
		t.Fatalf("self = %+v", got.Self)
	}

	// 在线的在前（按名字序），离线的在后
	var order []string
	for _, p := range got.Peers {
		order = append(order, p.Name)
	}
	if want := []string{"hmm", "hu", "idle-box", "mba", "viaa", "op12"}; !reflect.DeepEqual(order, want) {
		t.Fatalf("order = %v, want %v", order, want)
	}

	byName := map[string]tailnetPeer{}
	for _, p := range got.Peers {
		byName[p.Name] = p
	}
	wantPath := map[string]string{"hu": "direct", "mba": "direct", "hmm": "relay", "viaa": "peer-relay", "idle-box": "idle", "op12": "offline"}
	for n, w := range wantPath {
		if byName[n].Path != w {
			t.Errorf("%s path = %q, want %q", n, byName[n].Path, w)
		}
	}
	if byName["hmm"].Relay != "hkg" {
		t.Errorf("hmm relay = %q", byName["hmm"].Relay)
	}
	if byName["op12"].LastSeen != "2026-10-01T00:30:00Z" {
		t.Errorf("op12 lastSeen = %q", byName["op12"].LastSeen)
	}
	if byName["hmm"].LastSeen != "" {
		t.Errorf("在线节点不该带 lastSeen，got %q", byName["hmm"].LastSeen)
	}
	if byName["hu"].OS != "linux" {
		t.Errorf("hu os = %q", byName["hu"].OS)
	}
}

func TestBuildTailnetStatusNoSelf(t *testing.T) {
	if _, ok := buildTailnetStatus(nil, "pjd110-petrel"); ok {
		t.Error("nil status should not be ok")
	}
	if _, ok := buildTailnetStatus(&ipnstate.Status{}, "pjd110-petrel"); ok {
		t.Error("status without Self should not be ok")
	}
}

func TestBuildTailnetStatusSelfFallbacks(t *testing.T) {
	st := &ipnstate.Status{TailscaleIPs: ips("100.64.0.20"), Self: &ipnstate.PeerStatus{}}
	got, _ := buildTailnetStatus(st, "pjd110-petrel")
	if got.Self.Name != "pjd110-petrel" || !reflect.DeepEqual(got.Self.IPs, []string{"100.64.0.20"}) {
		t.Fatalf("self = %+v", got.Self)
	}
}

func TestTailnetStatusJSONShape(t *testing.T) {
	got, _ := buildTailnetStatus(&ipnstate.Status{Self: &ipnstate.PeerStatus{DNSName: "a.b."}}, "pjd110-petrel")
	b, _ := json.Marshal(got)
	// 没有对端时 peers 必须是 []，不能是 null：Kotlin 侧按数组读
	if want := `{"self":{"name":"a","ips":[]},"peers":[]}`; string(b) != want {
		t.Fatalf("json = %s, want %s", b, want)
	}
}

func TestTailnetStatusNotRunning(t *testing.T) {
	if got := TailnetStatus(); got != "{}" {
		t.Fatalf("TailnetStatus without server = %q", got)
	}
	if err := Logout(); err == nil {
		t.Fatal("Logout without server should fail")
	}
}

func TestLoginGate(t *testing.T) {
	var g loginGate
	needs, running := ipn.NeedsLogin.String(), ipn.Running.String()
	steps := []struct {
		state, url string
		want       bool
	}{
		{needs, "", true},                     // 首次进入 NeedsLogin：请求登录
		{needs, "", false},                    // 链接还没到又来一条通知：不重复请求
		{needs, "https://example/a/1", false}, // 链接到了
		{running, "", false},                  // 批准后 Running：复位
		{needs, "", true},                     // 登出后再进 NeedsLogin：必须再请求
		{ipn.Starting.String(), "", false},    // 其它状态不触发
		{needs, "", false},                    // 同一轮里已经请求过
	}
	for i, s := range steps {
		if got := g.observe(s.state, s.url); got != s.want {
			t.Fatalf("step %d (%s,%q): got %v want %v", i, s.state, s.url, got, s.want)
		}
	}
}

func TestLoginGateReset(t *testing.T) {
	var g loginGate
	needs := ipn.NeedsLogin.String()
	if !g.observe(needs, "") {
		t.Fatal("first NeedsLogin must request")
	}
	if g.observe(needs, "") {
		t.Fatal("must not request twice")
	}
	g.reset() // StartLogin 失败且放弃
	if !g.observe(needs, "") {
		t.Fatal("after reset the next NeedsLogin must request again")
	}
}

func TestLoginBackoff(t *testing.T) {
	want := []time.Duration{2 * time.Second, 4 * time.Second, 8 * time.Second, 16 * time.Second, 30 * time.Second, 30 * time.Second}
	for i, w := range want {
		if got := loginBackoff(i); got != w {
			t.Errorf("loginBackoff(%d) = %v, want %v", i, got, w)
		}
	}
	if got := loginBackoff(1000); got != 30*time.Second {
		t.Errorf("loginBackoff(1000) = %v, want 30s", got)
	}
}

func noSleep(context.Context, time.Duration) bool { return true }

func TestRetryLoginRetriesUntilSuccess(t *testing.T) {
	calls, errs, gaveUp := 0, 0, 0
	retryLogin(context.Background(),
		func() error {
			calls++
			if calls < 3 {
				return errors.New("timeout")
			}
			return nil
		},
		noSleep,
		func() bool { return true },
		func(int) time.Duration { return 0 },
		func() { gaveUp++ },
		func(error) { errs++ },
	)
	if calls != 3 || errs != 2 || gaveUp != 0 {
		t.Fatalf("calls=%d errs=%d gaveUp=%d, want 3/2/0", calls, errs, gaveUp)
	}
}

func TestRetryLoginGivesUpWhenNoLongerNeeded(t *testing.T) {
	calls, gaveUp := 0, 0
	retryLogin(context.Background(),
		func() error { calls++; return errors.New("timeout") },
		noSleep,
		func() bool { return false }, // 等待期间已经登录 / 链接已经到了
		func(int) time.Duration { return 0 },
		func() { gaveUp++ },
		func(error) {},
	)
	if calls != 1 || gaveUp != 1 {
		t.Fatalf("calls=%d gaveUp=%d, want 1/1", calls, gaveUp)
	}
}

func TestRetryLoginStopsOnCancel(t *testing.T) {
	calls, gaveUp := 0, 0
	retryLogin(context.Background(),
		func() error { calls++; return errors.New("timeout") },
		func(context.Context, time.Duration) bool { return false }, // ctx 已结束（Stop）
		func() bool { return true },
		func(int) time.Duration { return 0 },
		func() { gaveUp++ },
		func(error) {},
	)
	if calls != 1 || gaveUp != 0 {
		t.Fatalf("calls=%d gaveUp=%d, want 1/0 (no reset on cancel)", calls, gaveUp)
	}
}
