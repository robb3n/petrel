package ptcore

import (
	"context"
	"maps"
	"net"
	"net/netip"
	"testing"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/adapter/provider"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

type dnsTestProxy struct {
	C.Proxy
	alive  bool
	delay  uint16
	dialer string
}

func (p *dnsTestProxy) AliveForTestUrl(string) bool { return p.alive }
func (p *dnsTestProxy) LastDelayForTestUrl(string) uint16 {
	if !p.alive {
		return 65535
	}
	return p.delay
}
func (p *dnsTestProxy) ProxyInfo() C.ProxyInfo { return C.ProxyInfo{DialerProxy: p.dialer} }
func dnsTestNode(name string) *dnsTestProxy {
	return &dnsTestProxy{Proxy: adapter.NewProxy(outbound.NewDirectWithOption(outbound.DirectOption{Name: name})), alive: true, delay: 10}
}

func dnsTestGroup(t *testing.T, name, kind string, members ...C.Proxy) C.Proxy {
	t.Helper()
	hc := provider.NewHealthCheck(members, "", 0, 0, true, nil)
	pd, err := provider.NewCompatibleProvider(name, members, hc)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pd.Close() })
	opts := outboundgroup.GroupCommonOption{Name: name, URL: "https://probe.invalid"}
	providers := []P.ProxyProvider{pd}
	var g outboundgroup.ProxyGroup
	switch kind {
	case "select":
		g, err = outboundgroup.NewSelector(opts, outboundgroup.SelectorOption{}, members[0], providers)
	case "fallback":
		g, err = outboundgroup.NewFallback(opts, outboundgroup.FallbackOption{}, members[0], providers)
	case "url-test":
		g, err = outboundgroup.NewURLTest(opts, outboundgroup.URLTestOption{}, members[0], providers)
	case "load-balance":
		g, err = outboundgroup.NewLoadBalance(opts, outboundgroup.LoadBalanceOption{Strategy: "round-robin"}, members[0], providers)
	default:
		t.Fatal(kind)
	}
	if err != nil {
		t.Fatal(err)
	}
	return adapter.NewProxy(g)
}

func dnsTestTrack(t *testing.T, mgr *statistic.Manager, typ C.Type, ip string, port uint16, chain ...C.Proxy) statistic.Tracker {
	t.Helper()
	a, b := net.Pipe()
	conn := outbound.NewConn(a, chain[0])
	for _, p := range chain[1:] {
		conn.AppendToChains(p)
	}
	tr := statistic.NewTCPTracker(conn, mgr, &C.Metadata{Type: typ, DstIP: netip.MustParseAddr(ip), DstPort: port}, nil, 0, 0, false)
	t.Cleanup(func() { _ = tr.Close(); _ = b.Close() })
	return tr
}

func TestDNSRoutesExternalAndAutomaticSwitch(t *testing.T) {
	for _, kind := range []string{"select", "fallback", "url-test"} {
		t.Run(kind, func(t *testing.T) {
			jp, us := dnsTestNode("jp"), dnsTestNode("us")
			us.delay = 50
			auto := dnsTestGroup(t, "AUTO", kind, jp, us)
			root := dnsTestGroup(t, "PROXY", "select", auto)
			// Provider members intentionally absent from the top-level map.
			all := map[string]C.Proxy{"PROXY": root}
			mgr := &statistic.Manager{}
			w := &dnsRouteWatch{root: "PROXY", last: snapshotDNSRoute("PROXY", all)}
			if w.last["AUTO"].next != "jp" {
				t.Fatal("wrong initial selection")
			}
			cf := dnsTestTrack(t, mgr, C.INNER, "1.1.1.1", 443, jp, auto, root)
			google := dnsTestTrack(t, mgr, C.INNER, "8.8.8.8", 443, jp, auto, root)
			web := dnsTestTrack(t, mgr, C.TUN, "1.1.1.1", 443, jp, auto, root)
			domestic := dnsTestTrack(t, mgr, C.INNER, "223.5.5.5", 443, jp)
			other := dnsTestTrack(t, mgr, C.INNER, "9.9.9.9", 443, jp, auto, root)
			clears := 0
			clear := func() { clears++ }
			if changed, n := w.reconcile(all, mgr, clear); changed || n != 0 || clears != 0 {
				t.Fatal("stable route disturbed")
			}
			switch kind {
			case "select":
				// Same upstream Set used by REST, bypassing Petrel SelectProxy.
				if err := auto.Adapter().(outboundgroup.SelectAble).Set("us"); err != nil {
					t.Fatal(err)
				}
			case "fallback":
				jp.alive = false // genuine automatic fallback, no Set
			case "url-test":
				jp.alive = false
				// Reset the upstream 10s memoizer, as a completed health check does.
				// Empty selection leaves automatic fastest-node selection enabled.
				auto.Adapter().(outboundgroup.SelectAble).ForceSet("")
			}
			changed, n := w.reconcile(all, mgr, clear)
			if !changed || n != 2 || clears != 1 {
				t.Fatalf("switch: changed=%v closed=%d clears=%d", changed, n, clears)
			}
			for _, tr := range []statistic.Tracker{cf, google} {
				if mgr.Get(tr.ID()) != nil {
					t.Fatal("stale DNS survived")
				}
			}
			for _, tr := range []statistic.Tracker{web, domestic, other} {
				if mgr.Get(tr.ID()) == nil {
					t.Fatal("unrelated connection closed")
				}
			}
			fresh := dnsTestTrack(t, mgr, C.INNER, "1.1.1.1", 443, us, auto, root)
			if changed, n = w.reconcile(all, mgr, clear); changed || n != 0 || clears != 1 {
				t.Fatal("stable route cleared twice")
			}
			// A dial started on the old route can finish after the first sweep.
			late := dnsTestTrack(t, mgr, C.INNER, "8.8.8.8", 443, jp, auto, root)
			if changed, n = w.reconcile(all, mgr, clear); changed || n != 1 || clears != 2 {
				t.Fatal("late old dial not removed")
			}
			if mgr.Get(late.ID()) != nil || mgr.Get(fresh.ID()) == nil {
				t.Fatal("late cleanup hit fresh DNS")
			}
		})
	}
}

func TestDNSRoutesDialerAndInactiveBranches(t *testing.T) {
	a, b := dnsTestNode("front-a"), dnsTestNode("front-b")
	front := dnsTestGroup(t, "front", "select", a, b)
	jp, us := dnsTestNode("jp"), dnsTestNode("us")
	jp.dialer = "front"
	unused := dnsTestGroup(t, "unused", "select", a, b)
	root := dnsTestGroup(t, "PROXY", "select", jp, us)
	all := map[string]C.Proxy{"PROXY": root, "front": front, "unused": unused}
	mgr := &statistic.Manager{}
	w := &dnsRouteWatch{root: "PROXY", last: snapshotDNSRoute("PROXY", all)}
	clears := 0
	clear := func() { clears++ }
	_ = unused.Adapter().(outboundgroup.SelectAble).Set("front-b")
	if changed, _ := w.reconcile(all, mgr, clear); changed || clears != 0 {
		t.Fatal("inactive branch triggered cleanup")
	}
	dns := dnsTestTrack(t, mgr, C.INNER, "1.1.1.1", 443, jp, root)
	_ = front.Adapter().(outboundgroup.SelectAble).Set("front-b")
	if changed, n := w.reconcile(all, mgr, clear); !changed || n != 1 || clears != 1 {
		t.Fatal("dialer-proxy change missed")
	}
	if mgr.Get(dns.ID()) != nil {
		t.Fatal("old DNS survived front switch")
	}
	// A provider can replace a node while retaining its display name.
	all["front"] = dnsTestGroup(t, "front", "select", dnsTestNode("front-b"))
	if changed, _ := w.reconcile(all, mgr, clear); !changed || clears != 2 {
		t.Fatal("same-name replacement missed")
	}
}

func TestDNSRoutesLoadBalanceDoesNotRotate(t *testing.T) {
	a, b := dnsTestNode("a"), dnsTestNode("b")
	lb := dnsTestGroup(t, "PROXY", "load-balance", a, b)
	all := map[string]C.Proxy{"PROXY": lb}
	first := snapshotDNSRoute("PROXY", all)
	for range 5 {
		if !maps.Equal(first, snapshotDNSRoute("PROXY", all)) {
			t.Fatal("snapshot rotated balancing group")
		}
	}
	if first["PROXY"].next != "" || len(first) != 3 {
		t.Fatal("load-balance has no unique exit")
	}
	if staleDNSChain(C.Chain{"a", "PROXY"}, first) || staleDNSChain(C.Chain{"b", "PROXY"}, first) {
		t.Fatal("valid balancing member marked stale")
	}
}

func TestDNSRoutesCycle(t *testing.T) {
	a := dnsTestNode("a")
	a.dialer = "a"
	if got := snapshotDNSRoute("a", map[string]C.Proxy{"a": a}); len(got) != 1 {
		t.Fatal(got)
	}
}

func TestDNSRoutesOptOutAndSessionIsolation(t *testing.T) {
	for _, tc := range []struct{ src, want string }{
		{validMinimal, "PROXY"},
		{validMinimal + "\npetrel-dns: {mode: original}\n", ""},
		{string(tailnetOnlyConfig), ""},
		{validMinimal + "\npetrel-dns: {proxy: PROXY}\n", "PROXY"},
	} {
		if got := managedDNSRoot([]byte(tc.src)); got != tc.want {
			t.Fatalf("root %q want %q", got, tc.want)
		}
	}
	old := activeDNSRoutes
	defer func() { activeDNSRoutes = old }()
	w := &dnsRouteWatch{root: "PROXY"}
	activeDNSRoutes = w
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if pollDNSRoutes(ctx, w, currentGen()) {
		t.Fatal("cancelled session polled")
	}
	if pollDNSRoutes(context.Background(), &dnsRouteWatch{}, currentGen()) {
		t.Fatal("old watcher polled new session")
	}
	if pollDNSRoutes(context.Background(), w, currentGen()-1) {
		t.Fatal("old generation polled")
	}
}
