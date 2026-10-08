package ptcore

import (
	"context"
	"maps"
	"slices"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	"go.yaml.in/yaml/v3"
)

// Upstream has no selection-change subscription. Poll only the managed DNS
// route, without dialing or touching health checks. This is eventual cleanup,
// not an atomic barrier around external PUTs or upstream health-check switches.
const dnsRouteInterval = 2 * time.Second

// All accesses are under lifecycleMu, including the synchronous UI path.
var activeDNSRoutes *dnsRouteWatch

type dnsRouteHop struct {
	proxy  C.Proxy
	next   string // selected member; empty for leaves and load balancing
	dialer string
}
type dnsRouteSnapshot map[string]dnsRouteHop

type dnsRouteWatch struct {
	root string
	last dnsRouteSnapshot
}

// Called only after injectConfig succeeded. Reuse the exact main-proxy choice;
// original and tailnet-only configurations must not acquire a managed watcher.
func managedDNSRoot(user []byte) string {
	var cfg map[string]any
	if yaml.Unmarshal(user, &cfg) != nil {
		return ""
	}
	p, _ := cfg[dnsPolicyKey].(map[string]any)
	if p["mode"] == "original" {
		return ""
	}
	requested, _ := p["proxy"].(string)
	root, _ := dnsProxy(cfg, requested)
	return root
}

// Follow nested groups AND dialer-proxy dependencies. A provider's members need
// not occur in tunnel.Proxies, so walk the actual Proxy objects, not just names.
// For load balancing there is no unique selected member: watch its dependency
// set without calling Unwrap (which would advance round-robin selection).
func snapshotDNSRoute(root string, all map[string]C.Proxy) dnsRouteSnapshot {
	out := dnsRouteSnapshot{}
	var visit func(C.Proxy)
	visit = func(p C.Proxy) {
		if p == nil {
			return
		}
		if _, seen := out[p.Name()]; seen {
			return
		}
		hop := dnsRouteHop{proxy: p, dialer: p.ProxyInfo().DialerProxy}
		out[p.Name()] = hop // break cycles before descending
		if g, ok := p.Adapter().(outboundgroup.ProxyGroup); ok {
			switch p.Type() {
			case C.Selector, C.URLTest, C.Fallback:
				selected := g.Unwrap(&C.Metadata{Type: C.INNER}, false)
				if selected != nil {
					hop.next = selected.Name()
					out[p.Name()] = hop
					visit(selected)
				}
			default:
				for _, member := range g.Proxies() {
					visit(member)
				}
			}
		}
		if hop.dialer != "" {
			visit(all[hop.dialer])
		}
	}
	visit(all[root])
	return out
}

func managedDNSTracker(info *statistic.TrackerInfo, root string) bool {
	if info == nil || info.Metadata == nil {
		return false
	}
	m := info.Metadata
	if m.Type != C.INNER || m.DstPort != 443 || !slices.Contains(info.Chain, root) {
		return false
	}
	// These are the two fixed managed DoH endpoints. Never sweep application
	// traffic, domestic DIRECT DNS, health checks, or unrelated inner connections.
	return m.DstIP.String() == "1.1.1.1" || m.DstIP.String() == "8.8.8.8"
}

func staleDNSChain(chain C.Chain, route dnsRouteSnapshot) bool {
	// Mihomo records leaf -> inner groups -> outer group. Checking each edge
	// catches nested changes and old dials completing after the first sweep.
	for i, name := range chain {
		if hop, ok := route[name]; ok && hop.next != "" && (i == 0 || chain[i-1] != hop.next) {
			return true
		}
	}
	return false
}

// reconcile is also used by integration tests with real upstream groups and
// trackers. Cleanup callbacks run synchronously; fake-IP storage is untouched.
func (w *dnsRouteWatch) reconcile(all map[string]C.Proxy, manager *statistic.Manager, clear func()) (changed bool, closed int) {
	now := snapshotDNSRoute(w.root, all)
	changed = !maps.Equal(w.last, now)
	manager.Range(func(t statistic.Tracker) bool {
		if managedDNSTracker(t.Info(), w.root) && (changed || staleDNSChain(t.Info().Chain, now)) {
			_ = t.Close()
			closed++
		}
		return true
	})
	if changed || closed > 0 {
		clear()
	}
	w.last = now
	return
}

func startDNSRoutes(ctx context.Context, user []byte) {
	root := managedDNSRoot(user)
	if root == "" {
		return
	}
	w := &dnsRouteWatch{root: root, last: snapshotDNSRoute(root, tunnel.Proxies())}
	activeDNSRoutes = w
	gen := currentGen()
	go func() {
		timer := time.NewTicker(dnsRouteInterval)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				if !pollDNSRoutes(ctx, w, gen) {
					return
				}
			}
		}
	}()
}

func pollDNSRoutes(ctx context.Context, w *dnsRouteWatch, gen int) bool {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	// Stop can cancel while this goroutine waits for the lifecycle lock; an old
	// session must never sweep connections belonging to a subsequent Start.
	if ctx.Err() != nil || activeDNSRoutes != w || currentGen() != gen || !running() {
		return false
	}
	changed, closed := w.reconcile(tunnel.Proxies(), statistic.DefaultManager, clearDNSCache)
	if changed || closed > 0 {
		logf("info", "dns route changed: refreshed cache, closed %d managed DNS connections", closed)
		bumpGroups(gen, currentExit(), true)
	}
	return true
}
