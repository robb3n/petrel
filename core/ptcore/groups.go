package ptcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/component/profile/cachefile"
	"github.com/metacubex/mihomo/component/proxydialer"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

const (
	defaultTestURL  = "https://www.gstatic.com/generate_204"
	testTimeout     = 5 * time.Second        // 单个节点的测速超时
	testWaitCap     = 6 * time.Second        // TestGroupDelay 阻塞的上限
	warmTotal       = 10 * time.Second       // 自动测速前预热 tailnet 第一跳的总上限，要盖过 wireguard-go 5 秒的握手重发
	warmAttempt     = 2 * time.Second        // 预热时单次拨号的超时
	warmRetryGap    = 200 * time.Millisecond // 预热拨号失败后到下一次的间隔
	warmSettle      = 3 * time.Second        // 首次连上之后继续拨号保持流量的时长，等两个方向的路径都换到直连
	warmSettleGap   = 300 * time.Millisecond // 稳定期内两次拨号的间隔
	warmFastDial    = 300 * time.Millisecond // 第一次拨号就在这么短的时间内连上，说明通路本来就是热的，不走稳定期
	probeFresh      = 25 * time.Second       // ProbeSelected 跳过这么久之内测过的节点；略短于界面 30 秒的探测周期
	maxChainDepth   = 8
	globalGroupName = "GLOBAL"
)

// 锁纪律同 core.go：下面所有调 mihomo 的函数都不持有 mu；只在锁内读写状态变量。

type proxyJSON struct {
	Name  string   `json:"name"`
	Type  string   `json:"type"`
	Chain []string `json:"chain"`
	// Relays 是 Chain 里由 petrel-via 标注出来的中转（不是 mihomo 节点）
	Relays []string `json:"relays"`
	Delay  int      `json:"delay"` // -1 没测过，0 失败 / 超时，其余为毫秒
	// Addr 是节点的服务器地址（host:port），组、DIRECT 等没有地址的为空串。Kotlin 用「名字 + 地址」给记下的出口 IP 作键
	Addr string `json:"addr"`
}

type groupJSON struct {
	Name    string      `json:"name"`
	Now     string      `json:"now"`
	TestURL string      `json:"testURL"`
	Proxies []proxyJSON `json:"proxies"`
}

// groupCandidate 是 tunnel.Proxies() 里一个代理的筛选所需信息，纯数据，便于单测。
type groupCandidate struct {
	Name     string
	Selector bool // Type() == C.Selector 且 adapter 实现了 outboundgroup.ProxyGroup
	Hidden   bool
}

// orderGroups 筛出可手动切换的组并排序：Selector、非 hidden、非 GLOBAL；
// 顺序跟随 GLOBAL 组成员的顺序（即配置文件里 proxy-groups 的顺序），不在其中的排最后并按名字排序。
func orderGroups(cands []groupCandidate, globalOrder []string) []string {
	index := make(map[string]int, len(globalOrder))
	for i, n := range globalOrder {
		if _, dup := index[n]; !dup {
			index[n] = i
		}
	}
	var names []string
	for _, c := range cands {
		if !c.Selector || c.Hidden || c.Name == globalGroupName {
			continue
		}
		names = append(names, c.Name)
	}
	sort.SliceStable(names, func(i, j int) bool {
		ii, iok := index[names[i]]
		jj, jok := index[names[j]]
		switch {
		case iok && jok:
			return ii < jj
		case iok != jok:
			return iok
		default:
			return names[i] < names[j]
		}
	})
	return names
}

// chainOf 从成员出发，沿 dialer-proxy 逐级找上一跳，返回「第一跳 → … → 成员自己」。
// dialerOf(name) 返回 name 的 dialer-proxy（没有则空串）；第二个返回值表示 name 是否是已知节点。
// 遇到环或引用了不存在的名字就截断，总长度上限 maxChainDepth。
func chainOf(name string, dialerOf func(string) (string, bool)) []string {
	rev := []string{name}
	seen := map[string]bool{name: true}
	cur := name
	for len(rev) < maxChainDepth {
		d, ok := dialerOf(cur)
		if !ok || d == "" || seen[d] {
			break
		}
		if _, known := dialerOf(d); !known {
			break
		}
		rev = append(rev, d)
		seen[d] = true
		cur = d
	}
	for i, j := 0, len(rev)-1; i < j; i, j = i+1, j-1 {
		rev[i], rev[j] = rev[j], rev[i]
	}
	return rev
}

// withVias 把中转标注插进链路：每一跳如果有 petrel-via，就在它前面加上中转名。返回新链路与其中的中转名。
func withVias(chain []string, vias map[string]string) (out []string, relays []string) {
	relays = []string{}
	for _, n := range chain {
		if v := vias[n]; v != "" {
			out = append(out, v)
			relays = append(relays, v)
		}
		out = append(out, n)
	}
	return out, relays
}

// delayValue 把 mihomo 的测速记录折成界面用的数值。
func delayValue(hasHistory, alive bool, last uint16) int {
	switch {
	case !hasHistory:
		return -1
	case !alive || last == 0 || last == 0xffff:
		return 0
	default:
		return int(last)
	}
}

// hopNode 是一个节点的 dialer-proxy 与服务器地址，纯数据，便于单测。
type hopNode struct {
	Dialer string
	Addr   string
}

// tailnetHopAddrs 挑出直接以 ts 为 dialer-proxy 的节点的服务器地址（去重、排序），即 tailnet 里的第一跳。
func tailnetHopAddrs(nodes []hopNode) []string {
	seen := map[string]bool{}
	var addrs []string
	for _, n := range nodes {
		if n.Dialer != tsProxyName || n.Addr == "" || seen[n.Addr] {
			continue
		}
		seen[n.Addr] = true
		addrs = append(addrs, n.Addr)
	}
	sort.Strings(addrs)
	return addrs
}

// probeCandidate 是一个组当前选中项的探测所需信息，纯数据，便于单测。
type probeCandidate struct {
	Group   string
	Member  string
	URL     string
	Testing bool      // 组里正有一轮整组测速
	Last    time.Time // 该成员对 URL 最近一次测速的时间，没测过为零值
}

// pickProbes 挑出要探测的组当前选中项：整组测速进行中的组跳过（结果马上会有）；
// force 为 false 时跳过 probeFresh 之内测过的；同一成员与 URL 只测一次。
func pickProbes(cands []probeCandidate, force bool, now time.Time) []probeCandidate {
	seen := map[[2]string]bool{}
	var out []probeCandidate
	for _, c := range cands {
		key := [2]string{c.Member, c.URL}
		if c.Member == "" || c.Testing || seen[key] {
			continue
		}
		if !force && !c.Last.IsZero() && now.Sub(c.Last) < probeFresh {
			continue
		}
		seen[key] = true
		out = append(out, c)
	}
	return out
}

// ---- 以下是 mihomo 适配层 ----

// running 在锁内读状态，锁外使用。
func running() bool {
	mu.Lock()
	defer mu.Unlock()
	return active && cur.VPN == "running"
}

func selectorGroup(p C.Proxy) (outboundgroup.ProxyGroup, bool) {
	if p == nil || p.Type() != C.Selector {
		return nil, false
	}
	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	return g, ok
}

// groupNames 返回按配置顺序排好的可手动切换组名。
func groupNames(all map[string]C.Proxy) []string {
	var cands []groupCandidate
	for name, p := range all {
		g, ok := selectorGroup(p)
		cands = append(cands, groupCandidate{Name: name, Selector: ok, Hidden: ok && g.Hidden()})
	}
	var order []string
	if g, ok := all[globalGroupName]; ok {
		if pg, ok := g.Adapter().(outboundgroup.ProxyGroup); ok {
			for _, m := range pg.Proxies() {
				order = append(order, m.Name())
			}
		}
	}
	return orderGroups(cands, order)
}

func dialerLookup(all map[string]C.Proxy) func(string) (string, bool) {
	return func(name string) (string, bool) {
		p, ok := all[name]
		if !ok {
			return "", false
		}
		return p.ProxyInfo().DialerProxy, true
	}
}

func groupTestURL(g C.Proxy) string {
	raw, err := g.Adapter().MarshalJSON()
	if err == nil {
		var m struct {
			TestURL string `json:"testUrl"`
		}
		if json.Unmarshal(raw, &m) == nil && m.TestURL != "" {
			return m.TestURL
		}
	}
	return defaultTestURL
}

func memberDelay(p C.Proxy, url string) int {
	st, ok := p.ExtraDelayHistories()[url]
	if !ok || len(st.History) == 0 {
		return -1
	}
	return delayValue(true, p.AliveForTestUrl(url), p.LastDelayForTestUrl(url))
}

func currentVias() map[string]string {
	mu.Lock()
	defer mu.Unlock()
	return vias // Start 整份替换、不原地改，锁外只读是安全的
}

func snapshotGroups() []groupJSON {
	all := tunnel.Proxies()
	dialerOf := dialerLookup(all)
	viaMap := currentVias()
	out := []groupJSON{}
	for _, name := range groupNames(all) {
		p := all[name]
		g, _ := selectorGroup(p)
		url := groupTestURL(p)
		gj := groupJSON{Name: name, Now: g.Now(), TestURL: url, Proxies: []proxyJSON{}}
		for _, m := range g.Proxies() {
			chain, relays := withVias(chainOf(m.Name(), dialerOf), viaMap)
			gj.Proxies = append(gj.Proxies, proxyJSON{
				Name:   m.Name(),
				Type:   m.Type().String(),
				Chain:  chain,
				Relays: relays,
				Delay:  memberDelay(m, url),
				Addr:   m.Addr(),
			})
		}
		out = append(out, gj)
	}
	return out
}

// currentExit 返回第一个可切换组当前选中的节点名，没有组时为空串。
func currentExit() string {
	all := tunnel.Proxies()
	names := groupNames(all)
	if len(names) == 0 {
		return ""
	}
	g, _ := selectorGroup(all[names[0]])
	return g.Now()
}

// Groups 返回所有可手动切换的组（JSON）；VPN 未运行时返回 "[]"。
func Groups() string {
	if !running() {
		return "[]"
	}
	b, err := json.Marshal(snapshotGroups())
	if err != nil {
		return "[]"
	}
	return string(b)
}

// bumpGroups 在锁外算好 exit 后进锁赋值，出锁后 publish。
// gen 是发起这次操作时的 testGen：之后又 Start 过（换了一代）就什么都不做，旧一轮的残留不能动新一轮的 groupsRev / exit。
func bumpGroups(gen int, exit string, updateExit bool) {
	mu.Lock()
	if gen != testGen {
		mu.Unlock()
		return
	}
	if updateExit {
		cur.Exit = exit
	}
	cur.GroupsRev++
	mu.Unlock()
	publish()
}

func currentGen() int {
	mu.Lock()
	defer mu.Unlock()
	return testGen
}

// SelectProxy 切换组的选中项：Set → cachefile 持久化 → 断开链路经过该组的现有连接 → exit/groupsRev 更新并 publish。
func SelectProxy(group string, name string) error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if !running() {
		return errors.New("not running")
	}
	gen := currentGen()
	all := tunnel.Proxies()
	p, ok := all[group]
	if !ok {
		return fmt.Errorf("group %q not found", group)
	}
	if _, ok := selectorGroup(p); !ok {
		return fmt.Errorf("%q is not a selector group", group)
	}
	sel, ok := p.Adapter().(outboundgroup.SelectAble)
	if !ok {
		return fmt.Errorf("%q is not a selector group", group)
	}
	if err := sel.Set(name); err != nil {
		return fmt.Errorf("select %q in %q: %w", name, group, err)
	}
	cachefile.Cache().SetSelected(group, name)
	closed := closeConnectionsThrough(group)
	// Close old upstream transports first, then clear real DNS answers. Keep the
	// fake-IP mapping: apps can still be holding those synthetic addresses.
	clearDNSCache()
	if activeDNSRoutes != nil {
		activeDNSRoutes.reconcile(tunnel.Proxies(), statistic.DefaultManager, clearDNSCache)
	}
	logf("info", "select: %s -> %s (closed %d connections)", group, name, closed)

	bumpGroups(gen, currentExit(), true)
	return nil
}

func clearDNSCache() {
	// Do this synchronously, so successful selection means the cache is cleared.
	if resolver.DefaultResolver != nil {
		resolver.DefaultResolver.ClearCache()
	}
	resolver.SystemResolver.ClearCache()
}

// closeConnectionsThrough 断开链路里含该组名的现有连接，让切换立刻生效。
func closeConnectionsThrough(group string) int {
	n := 0
	statistic.DefaultManager.Range(func(t statistic.Tracker) bool {
		for _, hop := range t.Info().Chain {
			if hop == group {
				_ = t.Close()
				n++
				break
			}
		}
		return true
	})
	return n
}

// warmTailnetHops 经 ts 向每个 tailnet 第一跳反复发起 TCP 连接，直到连上（连上即关）或总时长到 warmTotal，各地址并发。
// tsnet 报 Running 时到对端的 WireGuard 还没握手，有时连 netmap 都还是缓存的旧的。首个握手包只能经对端的 home DERP 送出：
// 那条 DERP 连接还没建好时要排队（从国内连海外 DERP 要一两秒），被丢了就要等 wireguard-go 的 5 秒重发。
// 不预热的话，这几秒整个算进第一轮测速，延迟虚高（docs/lessons/network-change.md「首轮测速」）。
// 单次拨号限 warmAttempt：握手卡住时 SYN 只是排在后面，换一次新拨号不会更慢，握手一好立即能连上。
// 首次连上后再拨 warmSettle：实测本机刚换到直连的头一秒内测速仍会整体多出一秒多（单次拨号耗时看不出来），
// 推测是对端回程还在中继；tailscale 只在有流量时做路径升级，所以这段时间持续拨号而不是干等。
// 第一次拨号就在 warmFastDial 内连上时通路本来就是热的（热通路实测 40 到 90 ms），跳过稳定期，免得手动刷新白等三秒。
// 失败不影响后续测速，只记数量；日志里不带地址（属于配置内容）。
func warmTailnetHops() {
	if currentMode() == ModeProxy {
		return // ts 是占位节点，拨了也只是立即失败
	}
	all := tunnel.Proxies()
	ts, ok := all[tsProxyName]
	if !ok {
		return
	}
	nodes := make([]hopNode, 0, len(all))
	for _, p := range all {
		nodes = append(nodes, hopNode{Dialer: p.ProxyInfo().DialerProxy, Addr: p.Addr()})
	}
	addrs := tailnetHopAddrs(nodes)
	if len(addrs) == 0 {
		return
	}

	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), warmTotal)
	defer cancel()
	d := proxydialer.New(ts, false) // 与 mihomo 处理 dialer-proxy 时同一条路径
	var wg sync.WaitGroup
	var failed, attempts atomic.Int32
	settleDials := func(addr string) {
		end := time.Now().Add(warmSettle)
		for time.Now().Before(end) && sleepCtx(ctx, warmSettleGap) {
			attempts.Add(1)
			actx, acancel := context.WithTimeout(ctx, warmAttempt)
			if c, err := d.DialContext(actx, "tcp", addr); err == nil {
				_ = c.Close()
			}
			acancel()
		}
	}
	for _, addr := range addrs {
		wg.Add(1)
		go func(addr string) {
			defer wg.Done()
			for first := true; ; first = false {
				attempts.Add(1)
				t0 := time.Now()
				actx, acancel := context.WithTimeout(ctx, warmAttempt)
				c, err := d.DialContext(actx, "tcp", addr)
				acancel()
				if err == nil {
					_ = c.Close()
					if !first || time.Since(t0) > warmFastDial {
						settleDials(addr)
					}
					return
				}
				// 立即失败（例如对端拒绝）时稍等再试，别空转
				if !sleepCtx(ctx, warmRetryGap) {
					failed.Add(1)
					return
				}
			}
		}(addr)
	}
	wg.Wait()
	logf("info", "warm tailnet hops: %d/%d connected in %d ms (%d dials)",
		len(addrs)-int(failed.Load()), len(addrs), time.Since(start).Milliseconds(), attempts.Load())
}

func groupTesting(group string) bool {
	mu.Lock()
	defer mu.Unlock()
	return testingGroups[group] != nil
}

func lastTested(p C.Proxy, url string) time.Time {
	st, ok := p.ExtraDelayHistories()[url]
	if !ok || len(st.History) == 0 {
		return time.Time{}
	}
	return st.History[len(st.History)-1].Time
}

// ProbeSelected 测各可切换组当前选中的节点（不测其余成员），测完 groupsRev+1 并 publish，让界面上的出口延迟保持新鲜。
// force 为 false 时跳过 probeFresh 之内测过的节点，所以界面周期调用、刚测过整组、回到前台时重复调用都不浪费；
// 切网后传 true。整组测速进行中的组不测。VPN 未运行时返回错误。
func ProbeSelected(force bool) error {
	if !running() {
		return errors.New("not running")
	}
	gen := currentGen()
	all := tunnel.Proxies()
	members := map[string]C.Proxy{}
	var cands []probeCandidate
	for _, name := range groupNames(all) {
		g, _ := selectorGroup(all[name])
		url := groupTestURL(all[name])
		now := g.Now()
		for _, m := range g.Proxies() {
			if m.Name() == now {
				members[now] = m
				cands = append(cands, probeCandidate{
					Group: name, Member: now, URL: url, Testing: groupTesting(name), Last: lastTested(m, url),
				})
				break
			}
		}
	}
	picks := pickProbes(cands, force, time.Now())
	if len(picks) == 0 {
		return nil
	}

	var wg sync.WaitGroup
	for _, c := range picks {
		wg.Add(1)
		go func(m C.Proxy, url string) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), testTimeout)
			defer cancel()
			_, _ = m.URLTest(ctx, url, nil) // 失败同样记进历史，界面显示「超时」
		}(members[c.Member], c.URL)
	}
	wg.Wait()
	bumpGroups(gen, "", false)
	logf("debug", "probe selected: %d node(s), force=%v", len(picks), force)
	return nil
}

// Refresh 是首页的刷新：预热 tailnet 第一跳，再测所有组的延迟（同 TestGroupDelay("")），阻塞到测完。
// tailnet 节点列表由 Kotlin 侧随后重新拉。VPN 未运行时返回错误。
func Refresh() error {
	if !running() {
		return errors.New("not running")
	}
	warmTailnetHops()
	return TestGroupDelay("")
}

// testRun 是某个组的一轮测速；done 在测完（不论成败）时关闭。
type testRun struct{ done chan struct{} }

// testingGroups 记录正在测速的组，由 mu 保护；每次 Start 换一张新表并让 testGen 加 1。
var testingGroups = map[string]*testRun{}

// beginTest 登记一轮测速。组里已经有一轮在跑时 started 为 false，返回那一轮，调用方等它即可。
func beginTest(group string) (run *testRun, started bool, gen int) {
	mu.Lock()
	defer mu.Unlock()
	if r := testingGroups[group]; r != nil {
		return r, false, testGen
	}
	r := &testRun{done: make(chan struct{})}
	testingGroups[group] = r
	return r, true, testGen
}

// endTest 结束一轮：只在表还是同一代时才删条目（Start 换表之后，旧一轮不能删新一轮的同名条目），done 一律关闭。
func endTest(group string, run *testRun, gen int) {
	mu.Lock()
	if gen == testGen && testingGroups[group] == run {
		delete(testingGroups, group)
	}
	mu.Unlock()
	close(run.done)
}

// waitRuns 等 runs 全部测完，最多 wait；返回没等到的那些。
func waitRuns(runs []*testRun, wait time.Duration) (pending []*testRun) {
	deadline := time.NewTimer(wait)
	defer deadline.Stop()
	for i, r := range runs {
		select {
		case <-r.done:
		case <-deadline.C:
			for _, rest := range runs[i:] {
				select {
				case <-rest.done:
				default:
					pending = append(pending, rest)
				}
			}
			return pending
		}
	}
	return nil
}

// TestGroupDelay 对组内所有成员并发测速，阻塞到测完（上限 6 秒），然后 groupsRev+1 并 publish。
// 组里已经有一轮在测（例如自动测速进行中）时不重复发起，而是等那一轮测完，所以「测速中…」不会提前结束。
// 超过上限还没测完的组，在它们测完时再 groupsRev+1 一次。group 为空串表示测所有组（并发）。
func TestGroupDelay(group string) error {
	if !running() {
		return errors.New("not running")
	}
	all := tunnel.Proxies()
	var targets []string
	if group == "" {
		targets = groupNames(all)
	} else {
		if _, ok := selectorGroup(all[group]); !ok {
			return fmt.Errorf("group %q not found", group)
		}
		targets = []string{group}
	}

	runs := make([]*testRun, 0, len(targets))
	gen := currentGen()
	for _, name := range targets {
		run, started, g := beginTest(name)
		runs = append(runs, run)
		if !started {
			continue
		}
		pg, _ := selectorGroup(all[name])
		url := groupTestURL(all[name])
		go func(name string, pg outboundgroup.ProxyGroup, url string) {
			defer endTest(name, run, g)
			ctx, cancel := context.WithTimeout(context.Background(), testTimeout)
			defer cancel()
			// 全部失败时 URLTest 返回错误，失败本身已记进各成员的历史（界面显示「超时」），这里不当错误
			_, _ = pg.URLTest(ctx, url, nil)
		}(name, pg, url)
	}

	pending := waitRuns(runs, testWaitCap)
	if len(pending) > 0 {
		logf("warn", "test delay: still running after %s, giving up waiting", testWaitCap)
		go func() {
			for _, r := range pending {
				<-r.done
			}
			bumpGroups(gen, "", false)
		}()
	}
	bumpGroups(gen, "", false)
	logf("info", "test delay: done (%d groups)", len(targets))
	return nil
}
