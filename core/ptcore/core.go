// Package ptcore 是Petrel的 Go 内核层：上游 mihomo（不改源码）+ 本包持有的 tsnet 节点。
// mihomo 经 tsnet Loopback 的 SOCKS5 进入 tailnet（配置里的 ts 节点）。
// 导出面只用 gomobile 支持的类型，供 Android 侧调用。
package ptcore

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/common/observable"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/hub/route"
	"github.com/metacubex/mihomo/listener"
	LC "github.com/metacubex/mihomo/listener/config"
	mlog "github.com/metacubex/mihomo/log"

	"github.com/metacubex/tailscale/envknob"
	"github.com/metacubex/tailscale/ipn"
	"github.com/metacubex/tailscale/tsnet"
	"golang.org/x/sys/unix"
)

// Host 由 Android 侧实现。
type Host interface {
	// OnState 在状态变化时推送 JSON，形状同 Status()。
	OnState(json string)
	Log(level string, msg string)
}

type state struct {
	VPN        string   `json:"vpn"`
	Tailnet    string   `json:"tailnet"`
	TailnetIPs []string `json:"tailnetIPs"`
	LoginURL   string   `json:"loginURL"`
	Error      string   `json:"error"`
	// Exit 是第一个可切换组当前选中的节点名，没有组时为空串。
	Exit string `json:"exit"`
	// GroupsRev 在组数据（选中项或延迟）每变一次时加 1，Kotlin 据此重新拉 Groups()。每次 Start 从 0 开始。
	GroupsRev int `json:"groupsRev"`
}

// 锁纪律：mu 只保护下面这些变量，持有 mu 时绝不调用 tsnet / mihomo / Host。
// tailscale 会在持有内部锁时回调我们的 Logf，如果我们持有 mu 去调 tsnet、它又在 Logf 里等 mu，就会互相卡死。
//
// lifecycleMu 串行 Start / Stop（整段持有，期间可以调 tsnet / mihomo / Host），不与 mu 嵌套：拿 lifecycleMu 之后才可以拿 mu，反过来不行。
// parseMu 见 validate.go，只串行 mihomo 的配置解析。
var (
	lifecycleMu sync.Mutex

	mu       sync.Mutex
	srv      *tsnet.Server
	cancel   context.CancelFunc
	cur      = state{VPN: "stopped", Tailnet: "NoState", TailnetIPs: []string{}}
	netTimer *time.Timer
	// autoTested：本次 Start 是否已触发过自动测速；autoTestDone：那一轮是否已测完。每次 Start 复位
	autoTested   bool
	autoTestDone bool
	// testGen：每次 Start 加 1，测速记账（testingGroups / groupsRev）只认同一代，见 groups.go
	testGen int
	// vias：本次配置里的中转标注（节点名 → petrel-via），Start 时写入，推链路时读
	vias map[string]string

	host atomic.Pointer[hostBox] // 日志与状态推送的出口，无锁读取
)

type hostBox struct{ h Host }

// Start 起 tsnet 节点与 mihomo，tunFd 是 VpnService 交来的 TUN fd（所有权转给本包，成功后归 mihomo）。
// hostname 是本机在 tailnet 里的节点名，由 Android 侧首次注册时生成并持久化（见 docs/lessons/network-change.md「节点名」）。
// tsnet 立即启动，不等第一条连接；登录 URL 与状态经 Host.OnState 推出。
func Start(homeDir string, configPath string, tunFd int, hostname string, h Host) (err error) {
	// 与 Stop 串行：停止后立刻再启动时，新的 Start 要等旧的 Stop 收完；两个 Start 也不会同时通过下面的 srv 检查
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()

	// fd 从这里起归 Go 管：交给 mihomo 之前失败就在这里关掉；交出去之后由 mihomo 的清理流程负责，调用方不再碰它
	fdOwnedByMihomo := false
	defer func() {
		if err != nil && !fdOwnedByMihomo {
			_ = unix.Close(tunFd)
		}
	}()

	mu.Lock()
	if srv != nil {
		mu.Unlock()
		return errors.New("already running")
	}
	host.Store(&hostBox{h})
	cur = state{VPN: "starting", Tailnet: "NoState", TailnetIPs: []string{}}
	autoTested = false
	autoTestDone = false
	testingGroups = map[string]*testRun{}
	testGen++
	mu.Unlock()
	publish()

	defer func() {
		if err != nil {
			teardown()
			resetAfterFailure(err)
		}
	}()
	if strings.TrimSpace(hostname) == "" {
		return errors.New("empty tailnet hostname")
	}

	envknob.SetNoLogsNoSupport() // 不往 log.tailscale.io 上传日志
	tsDir := filepath.Join(homeDir, "tsnet")
	mihomoDir := filepath.Join(homeDir, "mihomo")
	for _, d := range []string{tsDir, mihomoDir} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			return fmt.Errorf("mkdir %s: %w", d, err)
		}
	}

	user, err := os.ReadFile(configPath)
	if err != nil {
		return fmt.Errorf("read config: %w", err)
	}

	s := &tsnet.Server{
		Dir:       tsDir,
		Hostname:  hostname,
		Ephemeral: false,
		Logf:      tsnetLogf,
		// tsnet 经 UserLogf 打出「… or go to: <登录链接>」，链接只许走 watchTailnet 的 `tailnet login URL:` 那一行
		UserLogf: func(format string, args ...any) {
			logf("info", "tsnet: %s", redactLoginURLs(fmt.Sprintf(format, args...)))
		},
	}
	mu.Lock()
	srv = s
	mu.Unlock()

	socksAddr, socksPass, _, err := s.Loopback() // 内部会 Start tsnet
	if err != nil {
		return fmt.Errorf("start tailnet: %w", err)
	}

	ctx, c := context.WithCancel(context.Background())
	mu.Lock()
	cancel = c
	mu.Unlock()
	go watchTailnet(ctx, s)
	// 订阅要在 ApplyConfig 之前同步建立，否则启动期的日志（包括 TUN 建立失败）会丢
	go forwardMihomoLogs(ctx, mlog.Subscribe())

	fallbackSecret, err := randomSecret()
	if err != nil {
		return fmt.Errorf("generate secret: %w", err)
	}
	injected, warnings, viaMap, err := injectConfig(user, tunFd, socksAddr, socksPass, fallbackSecret)
	if err != nil {
		// 带上前缀：Kotlin 侧日志只记第一个冒号之前的部分，后面的配置内容（节点名等）只给界面
		return fmt.Errorf("inject config: %w", err)
	}
	mu.Lock()
	vias = viaMap
	mu.Unlock()
	for _, w := range warnings {
		logf("warn", "config: %s", w)
	}

	// 解析失败时 fd 还没交出去，由上面的 defer 关；一旦进了 ApplyConfig，成败都不再由 defer 关
	if err := parseAndApplyConfig(mihomoDir, injected, func() { fdOwnedByMihomo = true }); err != nil {
		return fmt.Errorf("parse config: %w", err)
	}
	// ApplyConfig 不返回错误；TUN 建立失败时 mihomo 只打日志并把 enable 复位，这里据此判失败。
	// 失败时 mihomo 不会关 fd（详见 docs/lessons/vpn-tun-stack.md「TUN fd 的所有权」），由我们释放，否则 VPN 接口一直挂着、流量被吞
	if !listener.GetTunConf().Enable {
		releaseTunFd(tunFd)
		return errors.New("TUN 启动失败，详见 mihomo 日志")
	}

	exit := currentExit() // 调 mihomo，必须在锁外
	mu.Lock()
	cur.VPN = "running"
	cur.Exit = exit
	mu.Unlock()
	publish()
	maybeAutoTest()
	return nil
}

// resetAfterFailure 在 Start 失败、teardown 之后调用：与 Stop 一样整个复位，只留错误。
// 半截启动时 tailnet 可能已到 Running 或带着登录链接，不能留给界面。
func resetAfterFailure(err error) {
	mu.Lock()
	cur = state{VPN: "stopped", Tailnet: "Stopped", TailnetIPs: []string{}, Error: err.Error()}
	mu.Unlock()
	publish()
}

// releaseTunFd 在 mihomo 没能接管 TUN 时释放 fd，让 VPN 接口立刻拆掉。
// 不直接 close：mihomo 在 tunNew 之后才失败的路径上，fd 已被 os.File 包住却没人 Close，要等 GC 的 finalizer 去关；
// 我们先 close 的话，finalizer 日后会再关一次这个编号，而 Android 会复用编号，可能误关别的文件。
// 所以用 /dev/null 顶替这个编号：TUN 的引用当场释放，编号继续被占着，finalizer 日后关的只是占位。
// 代价是失败一次占一个编号，直到进程结束或 finalizer 运行。
//
// 顶替之前先确认这个编号现在还是 TUN：如果 mihomo / sing-tun 在已分析的两条路径之外已经把它关了（升级依赖后可能出现），
// 编号可能已被 tsnet 或 mihomo 的 socket 复用，这时再 dup2 会把 /dev/null 盖到活的 socket 上。不是 TUN 就不动。
func releaseTunFd(fd int) {
	target, err := os.Readlink(fmt.Sprintf("/proc/self/fd/%d", fd))
	if err != nil || !isTunLink(target) {
		logf("warn", "tun fd %d is no longer the tun device (%q, err=%v); leaving it alone", fd, target, err)
		return
	}
	replaceWithNull(fd)
}

// isTunLink 判断 /proc/self/fd/N 的链接目标是不是 TUN 设备（Android 上是 /dev/tun）。
func isTunLink(target string) bool { return target == "/dev/tun" || target == "/dev/net/tun" }

// replaceWithNull 用 /dev/null 顶替 fd 编号：原来的文件引用当场释放，编号继续被占着。
func replaceWithNull(fd int) {
	null, err := unix.Open("/dev/null", unix.O_RDWR|unix.O_CLOEXEC, 0)
	if err != nil {
		_ = unix.Close(fd)
		return
	}
	defer unix.Close(null)
	if err := unix.Dup2(null, fd); err != nil {
		_ = unix.Close(fd)
	}
}

// randomSecret 生成 16 字节随机 hex，作为用户配置没写 secret 时的兜底。
func randomSecret() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// takeAutoTest 判断此刻该不该触发自动测速；该触发时同时记下「本次 Start 已触发」，保证只触发一次。
// gen 是触发时的 testGen，供预热之后核对是否还是同一代。
func takeAutoTest() (gen int, ok bool) {
	mu.Lock()
	defer mu.Unlock()
	if autoTested || cur.VPN != "running" || cur.Tailnet != ipn.Running.String() {
		return 0, false
	}
	autoTested = true
	return testGen, true
}

// maybeAutoTest 在 VPN 运行与 tailnet Running 第一次同时成立时，后台给所有组测一次延迟；每次 Start 只触发一次。
// tailnet 没登录时测速必然全部失败，所以不在 VPN 一起来就测。Start 末尾与 watchTailnet 都要调用。
// 测之前先预热 tailnet 第一跳（见 warmTailnetHops），否则第一轮结果里会带上 WireGuard 首次握手的几秒。
func maybeAutoTest() {
	gen, ok := takeAutoTest()
	if !ok {
		return
	}
	logf("info", "auto test delay: vpn running and tailnet up, warming tailnet hops then testing all groups")
	go func() {
		warmTailnetHops()
		// 预热期间停止又启动过：新一代有自己的自动测速，这里不能抢在它预热完之前测一轮冷的
		if currentGen() != gen {
			return
		}
		if err := TestGroupDelay(""); err != nil {
			logf("warn", "auto test delay: %v", err)
		}
		mu.Lock()
		if gen == testGen {
			autoTestDone = true
		}
		mu.Unlock()
	}()
}

// Stop 关掉 mihomo 与 tsnet，幂等。
func Stop() {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	teardown()
	mu.Lock()
	cur = state{VPN: "stopped", Tailnet: "Stopped", TailnetIPs: []string{}}
	mu.Unlock()
	publish()
}

func teardown() {
	mu.Lock()
	s, c := srv, cancel
	srv, cancel = nil, nil
	if netTimer != nil {
		netTimer.Stop()
		netTimer = nil
	}
	mu.Unlock()

	if c != nil {
		c()
	}
	executor.Shutdown() // 关 TUN 等 listener（同时关闭 fd）
	// Shutdown 只关监听，不清 mihomo 记着的上一份 TUN 配置。Android 会复用 fd 编号，下次 Start 的配置
	// （含 file-descriptor）与旧的「相等」时，ReCreateTun 会认为没变而跳过重建，TUN 就成了死的（进程内停止再启动后断网）。
	listener.LastTunConf = LC.Tun{}
	route.ReCreateServer(&route.Config{}) // 关 external-controller
	if s != nil {
		if err := s.Close(); err != nil {
			logf("warn", "tsnet close: %v", err)
		}
	}
}

// Status 返回当前状态 JSON。
func Status() string {
	mu.Lock()
	defer mu.Unlock()
	return marshal(cur)
}

// StartLogin 触发交互登录，登录 URL 经 OnState 推出。
func StartLogin() error {
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
	ctx, c := context.WithTimeout(context.Background(), 15*time.Second)
	defer c()
	return lc.StartLoginInteractive(ctx)
}

// NotifyNetworkChanged 由 Android 的默认网络回调调用；去抖 1 秒后唤醒 tsnet 的网络监视器。
// tailscale 的 netmon 在 Android 上 10 分钟才轮询一次，靠系统侧主动通知；不通知的话切网后迟迟不恢复。
func NotifyNetworkChanged() {
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return
	}
	if netTimer != nil {
		netTimer.Stop()
	}
	netTimer = time.AfterFunc(time.Second, injectLinkChange)
}

// injectLinkChange 让 netmon 立即重算网络状态；状态有变时 LocalBackend 会重绑 magicsock 套接字并重新 STUN。
// 没用 LocalClient.DebugAction("rebind")：metacubex/tailscale 裁掉了 localapi 的 debug 处理，该调用会失败。
func injectLinkChange() {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return
	}
	mon, ok := s.Sys().NetMon.GetOK()
	if !ok || mon == nil {
		logf("warn", "network changed: tailnet monitor not ready")
		return
	}
	mon.InjectEvent()
	logf("info", "network changed: tailnet link monitor notified")
	go probeAfterNetworkChange()
}

// changeProbeBusy 防止接连的网络变化叠出多轮切网后探测。
var changeProbeBusy atomic.Bool

// probeAfterNetworkChange 在切网后预热 tailnet 第一跳，再探测各组当前选中的节点（不看新鲜度）。
// 切网后通路是冷的，旧的延迟已经不代表现状。自动测速还没测完时不做：启动期间也会来网络回调，那一轮自己会预热并测全部。
func probeAfterNetworkChange() {
	if !changeProbeBusy.CompareAndSwap(false, true) {
		return
	}
	defer changeProbeBusy.Store(false)
	mu.Lock()
	ready := cur.VPN == "running" && autoTestDone
	mu.Unlock()
	if !ready {
		return
	}
	warmTailnetHops()
	if err := ProbeSelected(true); err != nil {
		logf("warn", "probe after network change: %v", err)
	}
}

func watchTailnet(ctx context.Context, s *tsnet.Server) {
	lc, err := s.LocalClient()
	if err != nil {
		logf("error", "tailnet watch: %v", err)
		return
	}
	w, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
	if err != nil {
		logf("error", "tailnet watch: %v", err)
		return
	}
	defer w.Close()

	var gate loginGate
	loginNeeded := func() bool {
		mu.Lock()
		defer mu.Unlock()
		return cur.Tailnet == ipn.NeedsLogin.String() && cur.LoginURL == ""
	}
	for {
		n, err := w.Next()
		if err != nil {
			if ctx.Err() == nil {
				logf("warn", "tailnet watch ended: %v", err)
			}
			return
		}
		// 先在锁外向 tsnet 取地址，再进锁改状态（见文件头的锁纪律）
		var ips []string
		if n.State != nil && *n.State == ipn.Running {
			ips = tailnetIPs(s)
		}
		changed := false
		mu.Lock()
		// teardown 先取消 ctx、之后才复位状态（Stop、Start 失败）：取消之后这一轮不能再写，否则会盖掉复位、
		// 或把上一次的 tailnet 状态与登录链接写进下一次 Start 的状态。检查与写入同在 mu 下，没有缝
		if ctx.Err() != nil {
			mu.Unlock()
			return
		}
		if n.State != nil {
			cur.Tailnet = n.State.String()
			changed = true
			if *n.State == ipn.Running {
				cur.LoginURL = ""
				cur.TailnetIPs = ips
			} else {
				// 离开 Running（登出、停止）后地址已不属于本机，不能留在界面与通知里
				cur.TailnetIPs = []string{}
			}
		}
		if n.BrowseToURL != nil && *n.BrowseToURL != "" {
			cur.LoginURL = *n.BrowseToURL
			changed = true
		}
		tailnetState, loginURL := cur.Tailnet, cur.LoginURL
		mu.Unlock()

		if n.BrowseToURL != nil && *n.BrowseToURL != "" {
			logf("info", "tailnet login URL: %s", *n.BrowseToURL)
		}
		if gate.observe(tailnetState, loginURL) {
			// StartLogin 失败（超时、网络还没通）时带退避重试；放弃时复位 gate，下一条通知还能再触发
			go retryLogin(ctx, StartLogin, sleepCtx, loginNeeded, loginBackoff, gate.reset, func(err error) {
				logf("warn", "start login: %v", err)
			})
		}
		if changed {
			publish()
			maybeAutoTest()
		}
	}
}

func tailnetIPs(s *tsnet.Server) []string {
	ip4, ip6 := s.TailscaleIPs()
	ips := []string{}
	if ip4.IsValid() {
		ips = append(ips, ip4.String())
	}
	if ip6.IsValid() {
		ips = append(ips, ip6.String())
	}
	return ips
}

func forwardMihomoLogs(ctx context.Context, sub observable.Subscription[mlog.Event]) {
	defer mlog.UnSubscribe(sub)
	for {
		select {
		case <-ctx.Done():
			return
		case e, ok := <-sub:
			if !ok {
				return
			}
			// 订阅收到全部级别；按配置的 log-level 过滤，否则 debug 级 DNS 记录会冲掉 logcat 缓冲区
			if e.LogLevel < mlog.Level() {
				continue
			}
			logf(e.LogLevel.String(), "mihomo: %s", e.Payload)
		}
	}
}

// tsnetLogf 丢掉 tailscale 的 [v1]/[v2] 细节日志：它们每秒几十条，几十秒就冲掉 logcat 缓冲区。
func tsnetLogf(format string, args ...any) {
	if strings.Contains(format, "[v1]") || strings.Contains(format, "[v2]") {
		return
	}
	msg := fmt.Sprintf(format, args...)
	if strings.Contains(msg, "[v1]") || strings.Contains(msg, "[v2]") {
		return
	}
	// 控制面客户端会打「AuthURL is <登录链接>」
	logf("debug", "tsnet: %s", redactLoginURLs(msg))
}

// loginURLPattern 匹配 tailnet 的交互登录链接（控制面的 /a/<token> 路径），含官方与自建控制面。
var loginURLPattern = regexp.MustCompile(`https?://\S+/a/\S+`)

// redactLoginURLs 把 tsnet 日志里的登录链接换成占位：完整链接只许出现在 `tailnet login URL:` 那一行。
func redactLoginURLs(msg string) string {
	return loginURLPattern.ReplaceAllString(msg, "<login URL>")
}

func logf(level string, format string, args ...any) {
	if b := host.Load(); b != nil && b.h != nil {
		b.h.Log(level, fmt.Sprintf(format, args...))
	}
}

func publish() {
	mu.Lock()
	js := marshal(cur)
	mu.Unlock()
	if b := host.Load(); b != nil && b.h != nil {
		b.h.OnState(js)
	}
}

func marshal(s state) string {
	if s.TailnetIPs == nil {
		s.TailnetIPs = []string{}
	}
	b, _ := json.Marshal(s)
	return string(b)
}
