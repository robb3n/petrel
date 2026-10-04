package ptcore

import (
	"encoding/json"
	"reflect"
	"testing"
	"time"
)

func dialers(m map[string]string, known ...string) func(string) (string, bool) {
	k := map[string]bool{}
	for n := range m {
		k[n] = true
	}
	for _, n := range known {
		k[n] = true
	}
	return func(name string) (string, bool) {
		if !k[name] {
			return "", false
		}
		return m[name], true
	}
}

func TestChainOf(t *testing.T) {
	cases := []struct {
		name   string
		member string
		dialer func(string) (string, bool)
		want   []string
	}{
		{
			"三跳",
			"us-lax",
			dialers(map[string]string{"us-lax": "front", "front": "ts"}, "ts"),
			[]string{"ts", "front", "us-lax"},
		},
		{
			"没有 dialer-proxy",
			"solo",
			dialers(map[string]string{"solo": ""}),
			[]string{"solo"},
		},
		{
			"环被截断",
			"a",
			dialers(map[string]string{"a": "b", "b": "c", "c": "a"}),
			[]string{"c", "b", "a"},
		},
		{
			"自己指向自己",
			"a",
			dialers(map[string]string{"a": "a"}),
			[]string{"a"},
		},
		{
			"引用了不存在的名字",
			"a",
			dialers(map[string]string{"a": "ghost"}),
			[]string{"a"},
		},
		{
			"名字含空格和中文",
			"节点 一",
			dialers(map[string]string{"节点 一": "前置 节点"}, "前置 节点"),
			[]string{"前置 节点", "节点 一"},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := chainOf(c.member, c.dialer); !reflect.DeepEqual(got, c.want) {
				t.Errorf("chain = %v, want %v", got, c.want)
			}
		})
	}
}

func TestChainOfDepthLimit(t *testing.T) {
	m := map[string]string{}
	names := []string{"n0", "n1", "n2", "n3", "n4", "n5", "n6", "n7", "n8", "n9", "n10"}
	for i := 0; i+1 < len(names); i++ {
		m[names[i]] = names[i+1]
	}
	m["n10"] = ""
	got := chainOf("n0", dialers(m))
	if len(got) != maxChainDepth {
		t.Fatalf("len = %d, want %d: %v", len(got), maxChainDepth, got)
	}
	if got[len(got)-1] != "n0" {
		t.Errorf("last hop must be the member itself: %v", got)
	}
}

func TestOrderGroups(t *testing.T) {
	cands := []groupCandidate{
		{Name: "DIRECT"},                 // 不是 Selector
		{Name: "auto"},                   // url-test，不是 Selector
		{Name: "GLOBAL", Selector: true}, // 排除
		{Name: "秘密", Selector: true, Hidden: true},
		{Name: "Netflix", Selector: true},
		{Name: "PROXY", Selector: true},
		{Name: "Extra B", Selector: true},
		{Name: "Extra A", Selector: true},
	}
	order := []string{"DIRECT", "REJECT", "node1", "PROXY", "auto", "Netflix", "秘密"}
	got := orderGroups(cands, order)
	want := []string{"PROXY", "Netflix", "Extra A", "Extra B"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("groups = %v, want %v", got, want)
	}
}

func TestOrderGroupsEmpty(t *testing.T) {
	if got := orderGroups(nil, nil); len(got) != 0 {
		t.Errorf("want no groups, got %v", got)
	}
}

func TestDelayValue(t *testing.T) {
	cases := []struct {
		hasHistory, alive bool
		last              uint16
		want              int
	}{
		{false, true, 0, -1},
		{false, false, 0xffff, -1},
		{true, false, 0xffff, 0},
		{true, true, 0xffff, 0},
		{true, true, 0, 0},
		{true, true, 182, 182},
	}
	for _, c := range cases {
		if got := delayValue(c.hasHistory, c.alive, c.last); got != c.want {
			t.Errorf("delayValue(%v,%v,%d) = %d, want %d", c.hasHistory, c.alive, c.last, got, c.want)
		}
	}
}

func TestGroupJSONShape(t *testing.T) {
	g := []groupJSON{{
		Name: "PROXY", Now: "us lax", TestURL: defaultTestURL,
		Proxies: []proxyJSON{{Name: "us lax", Type: "Shadowsocks", Chain: []string{"ts", "us lax"}, Delay: 182}},
	}}
	b, err := json.Marshal(g)
	if err != nil {
		t.Fatal(err)
	}
	var back []map[string]any
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatal(err)
	}
	px := back[0]["proxies"].([]any)[0].(map[string]any)
	if back[0]["name"] != "PROXY" || back[0]["now"] != "us lax" || back[0]["testURL"] != defaultTestURL ||
		px["name"] != "us lax" || px["type"] != "Shadowsocks" || px["delay"].(float64) != 182 {
		t.Errorf("unexpected json: %s", b)
	}
}

func TestStateJSONHasExitAndGroupsRev(t *testing.T) {
	var m map[string]any
	if err := json.Unmarshal([]byte(marshal(state{VPN: "running", Exit: "us-lax", GroupsRev: 3})), &m); err != nil {
		t.Fatal(err)
	}
	if m["exit"] != "us-lax" || m["groupsRev"].(float64) != 3 {
		t.Errorf("state json = %v", m)
	}
	// 零值也必须带上这两个字段，Kotlin 侧不靠缺省
	var z map[string]any
	_ = json.Unmarshal([]byte(marshal(state{})), &z)
	if v, ok := z["exit"]; !ok || v != "" {
		t.Errorf("exit missing or non-empty in zero state: %v", z)
	}
	if v, ok := z["groupsRev"]; !ok || v.(float64) != 0 {
		t.Errorf("groupsRev missing in zero state: %v", z)
	}
}

func TestGroupsWhenNotRunning(t *testing.T) {
	if got := Groups(); got != "[]" {
		t.Errorf("Groups() = %q, want []", got)
	}
	if err := SelectProxy("PROXY", "x"); err == nil {
		t.Error("SelectProxy should fail when not running")
	}
	if err := TestGroupDelay(""); err == nil {
		t.Error("TestGroupDelay should fail when not running")
	}
}

func TestRandomSecret(t *testing.T) {
	a, err := randomSecret()
	if err != nil || len(a) != 32 {
		t.Fatalf("secret = %q, err = %v", a, err)
	}
	if b, _ := randomSecret(); a == b {
		t.Error("two secrets must differ")
	}
}

func TestTakeAutoTestFiresOncePerStart(t *testing.T) {
	mu.Lock()
	saved, savedFlag := cur, autoTested
	mu.Unlock()
	defer func() { mu.Lock(); cur, autoTested = saved, savedFlag; mu.Unlock() }()

	set := func(vpn, tailnet string, tested bool) {
		mu.Lock()
		cur = state{VPN: vpn, Tailnet: tailnet}
		autoTested = tested
		mu.Unlock()
	}
	set("starting", "Running", false)
	if _, ok := takeAutoTest(); ok {
		t.Error("must not fire while vpn is still starting")
	}
	set("running", "NeedsLogin", false)
	if _, ok := takeAutoTest(); ok {
		t.Error("must not fire before tailnet is Running")
	}
	set("running", "Running", false)
	gen, ok := takeAutoTest()
	if !ok {
		t.Fatal("must fire when vpn running and tailnet Running")
	}
	if gen != currentGen() {
		t.Errorf("gen = %d, want current testGen %d", gen, currentGen())
	}
	if _, ok := takeAutoTest(); ok {
		t.Error("must fire only once per Start")
	}
}

// resetTestState 清空测速记账并换一代，返回恢复函数。
func resetTestState(t *testing.T) {
	t.Helper()
	mu.Lock()
	savedCur, savedGroups, savedGen := cur, testingGroups, testGen
	cur = state{VPN: "running"}
	testingGroups = map[string]*testRun{}
	testGen++
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		cur, testingGroups, testGen = savedCur, savedGroups, savedGen
		mu.Unlock()
	})
}

func TestBeginTestJoinsRunningRound(t *testing.T) {
	resetTestState(t)
	r1, started, gen := beginTest("PROXY")
	if !started {
		t.Fatal("first beginTest must start a round")
	}
	r2, started, gen2 := beginTest("PROXY")
	if started || r2 != r1 || gen2 != gen {
		t.Fatalf("second beginTest must join the running round (started=%v same=%v)", started, r2 == r1)
	}
	endTest("PROXY", r1, gen)
	select {
	case <-r2.done:
	default:
		t.Fatal("joined waiter must be released when the round ends")
	}
	if _, started, _ := beginTest("PROXY"); !started {
		t.Fatal("a new round can start after the previous ended")
	}
}

func TestEndTestFromOldGenerationKeepsNewEntry(t *testing.T) {
	resetTestState(t)
	oldRun, _, oldGen := beginTest("PROXY")

	// Start 换了新一代：表被替换，新一轮登记了同名条目
	mu.Lock()
	testingGroups = map[string]*testRun{}
	testGen++
	mu.Unlock()
	newRun, started, _ := beginTest("PROXY")
	if !started {
		t.Fatal("new generation must be able to start its own round")
	}

	endTest("PROXY", oldRun, oldGen) // 旧一轮的残留 goroutine 收尾
	mu.Lock()
	got := testingGroups["PROXY"]
	mu.Unlock()
	if got != newRun {
		t.Fatal("old generation's endTest must not delete the new generation's entry")
	}
	select {
	case <-oldRun.done:
	default:
		t.Fatal("old round's done must still be closed")
	}
}

func TestBumpGroupsIgnoresOldGeneration(t *testing.T) {
	resetTestState(t)
	gen := currentGen()
	bumpGroups(gen, "", false)
	mu.Lock()
	rev := cur.GroupsRev
	testGen++
	mu.Unlock()
	if rev != 1 {
		t.Fatalf("groupsRev = %d, want 1", rev)
	}
	bumpGroups(gen, "late", true) // 旧一代
	mu.Lock()
	defer mu.Unlock()
	if cur.GroupsRev != 1 || cur.Exit != "" {
		t.Fatalf("old generation bump leaked into the new one: rev=%d exit=%q", cur.GroupsRev, cur.Exit)
	}
}

func TestWaitRuns(t *testing.T) {
	done := &testRun{done: make(chan struct{})}
	close(done.done)
	slow := &testRun{done: make(chan struct{})}

	if pending := waitRuns([]*testRun{done}, time.Second); len(pending) != 0 {
		t.Fatalf("finished runs must not be pending: %v", pending)
	}
	pending := waitRuns([]*testRun{done, slow}, 20*time.Millisecond)
	if len(pending) != 1 || pending[0] != slow {
		t.Fatalf("pending = %v, want only the slow run", pending)
	}

	// 等待期间测完的要被放行
	go func() { time.Sleep(10 * time.Millisecond); close(slow.done) }()
	if pending := waitRuns([]*testRun{slow}, time.Second); len(pending) != 0 {
		t.Fatalf("run finishing during the wait must not be pending: %v", pending)
	}
}

func TestTailnetHopAddrs(t *testing.T) {
	got := tailnetHopAddrs([]hopNode{
		{Dialer: "ts", Addr: "front-b:443"},
		{Dialer: "front-a", Addr: "exit:1080"}, // 第二跳，不经 ts
		{Dialer: "ts", Addr: "front-a:8388"},
		{Dialer: "ts", Addr: "front-b:443"}, // 重复
		{Dialer: "ts", Addr: ""},            // 组之类没有地址
		{Dialer: "", Addr: "direct:443"},
	})
	want := []string{"front-a:8388", "front-b:443"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("tailnetHopAddrs = %v, want %v", got, want)
	}
	if got := tailnetHopAddrs(nil); len(got) != 0 {
		t.Errorf("tailnetHopAddrs(nil) = %v, want empty", got)
	}
}

func TestPickProbes(t *testing.T) {
	now := time.Date(2026, 10, 4, 12, 0, 0, 0, time.UTC)
	url := "http://example.test/204"
	cands := []probeCandidate{
		{Group: "PROXY", Member: "a", URL: url, Last: now.Add(-40 * time.Second)}, // 过期，测
		{Group: "MEDIA", Member: "a", URL: url},                                   // 同一成员与 URL，只测一次
		{Group: "AI", Member: "b", URL: url, Last: now.Add(-10 * time.Second)},    // 刚测过
		{Group: "GAME", Member: "c", URL: url, Testing: true},                     // 整组测速中
		{Group: "EMPTY", Member: "", URL: url},                                    // 没有选中项
		{Group: "NEW", Member: "d", URL: url},                                     // 没测过，测
	}
	names := func(ps []probeCandidate) []string {
		var out []string
		for _, p := range ps {
			out = append(out, p.Group+"/"+p.Member)
		}
		return out
	}
	if got, want := names(pickProbes(cands, false, now)), []string{"PROXY/a", "NEW/d"}; !reflect.DeepEqual(got, want) {
		t.Errorf("pickProbes(force=false) = %v, want %v", got, want)
	}
	if got, want := names(pickProbes(cands, true, now)), []string{"PROXY/a", "AI/b", "NEW/d"}; !reflect.DeepEqual(got, want) {
		t.Errorf("pickProbes(force=true) = %v, want %v", got, want)
	}
}

func TestWithVias(t *testing.T) {
	vias := map[string]string{"tokyo": "relay", "front": "edge"}
	cases := []struct {
		chain, want, relays []string
	}{
		{[]string{"tokyo"}, []string{"relay", "tokyo"}, []string{"relay"}},
		{[]string{"ts", "front", "lax"}, []string{"ts", "edge", "front", "lax"}, []string{"edge"}},
		{[]string{"plain"}, []string{"plain"}, []string{}},
	}
	for _, c := range cases {
		got, relays := withVias(c.chain, vias)
		if !reflect.DeepEqual(got, c.want) || !reflect.DeepEqual(relays, c.relays) {
			t.Errorf("withVias(%v) = %v, %v; want %v, %v", c.chain, got, relays, c.want, c.relays)
		}
	}
	if got, _ := withVias([]string{"a"}, nil); !reflect.DeepEqual(got, []string{"a"}) {
		t.Errorf("nil vias: got %v", got)
	}
}
