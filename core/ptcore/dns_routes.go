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

// A DoH dial that began on the old route can register after the sweep that
// noticed the change. When the change is one a connection chain cannot show
// (see reconcile), everything registered within this window is swept once
// more. One TCP timeout for the front hop plus one for the node's own
// handshake; also the DoH client's own dial timeout.
const dnsLateDialWindow = 2 * C.DefaultTCPTimeout

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
	// lateUntil is non-zero while a late-dial window is open: the first round at
	// or after it closes managed DoH trackers registered up to that instant.
	lateUntil time.Time
	now       func() time.Time // nil = time.Now; tests move the clock
}

// chain is what mihomo records for a connection through root on this route:
// leaf -> inner groups -> root, following each unique selection.
func (r dnsRouteSnapshot) chain(root string) C.Chain {
	var names C.Chain
	for name := root; name != "" && len(names) <= len(r); name = r[name].next {
		names = append(C.Chain{name}, names...)
	}
	return names
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
	at := time.Now()
	if w.now != nil {
		at = w.now()
	}
	// A selection change on the main path leaves old dials with a chain the
	// later rounds recognise. Anything else (a dialer-proxy dependency, a
	// same-name replacement, balancing members) keeps the chain names intact:
	// the front hop is a separate tracker to the node's server, and a tracker
	// only records when the dial finished, not when it began. No test tells such
	// a late old dial from a fresh one, so sweep that window once when it ends.
	if changed && !staleDNSChain(w.last.chain(w.root), now) {
		w.lateUntil = at.Add(dnsLateDialWindow)
	}
	late := !w.lateUntil.IsZero() && !at.Before(w.lateUntil)
	manager.Range(func(t statistic.Tracker) bool {
		info := t.Info()
		if managedDNSTracker(info, w.root) &&
			(changed || staleDNSChain(info.Chain, now) || (late && !info.Start.After(w.lateUntil))) {
			_ = t.Close()
			closed++
		}
		return true
	})
	if late {
		w.lateUntil = time.Time{}
	}
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
	if changed {
		logf("info", "dns route changed: refreshed cache, closed %d managed DNS connections", closed)
	} else if closed > 0 { // same route: old-path dials that registered late (stale chain, or the window's sweep)
		logf("info", "dns route: refreshed cache, closed %d late managed DNS connections", closed)
	}
	if changed || closed > 0 {
		bumpGroups(gen, currentExit(), true)
	}
	return true
}
