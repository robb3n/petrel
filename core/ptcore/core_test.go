package ptcore

import (
	"os"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

type recHost struct{ logs chan string }

func (r *recHost) OnState(string)        {}
func (r *recHost) Log(_ string, m string) { r.logs <- m }

// tailscale 会在持有内部锁时回调 Logf；Logf 若去拿 mu，而另一路持有 mu 去调 tsnet，就会互相卡死
// （一加 12 实测：网络回调卡死在 NotifyNetworkChanged，切网后 tailnet 不再恢复）。
func TestTsnetLogfDoesNotTakeMu(t *testing.T) {
	h := &recHost{logs: make(chan string, 4)}
	host.Store(&hostBox{h})
	defer host.Store(nil)

	mu.Lock()
	defer mu.Unlock()
	done := make(chan struct{})
	go func() {
		tsnetLogf("magicsock: %s", "endpoint update")
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("tsnet Logf blocked while mu was held")
	}
	if got := <-h.logs; got != "tsnet: magicsock: endpoint update" {
		t.Errorf("log = %q", got)
	}
}

func TestTsnetLogfDropsVerbose(t *testing.T) {
	h := &recHost{logs: make(chan string, 4)}
	host.Store(&hostBox{h})
	defer host.Store(nil)

	tsnetLogf("control: [v1] PollNetMap: %s", "x")
	tsnetLogf("%s", "wg: [v2] handshake")
	tsnetLogf("LinkChange: %s", "major")
	if n := len(h.logs); n != 1 {
		t.Fatalf("want only the non-verbose line, got %d lines", n)
	}
	if got := <-h.logs; got != "tsnet: LinkChange: major" {
		t.Errorf("log = %q", got)
	}
}

func TestReplaceWithNullFreesInterfaceButKeepsNumber(t *testing.T) {
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer w.Close()
	// 取出读端的编号并脱离 *os.File 的管理，模拟 Kotlin 交来的裸 fd
	fd, err := unix.Dup(int(r.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	r.Close()

	replaceWithNull(fd)

	// 原来的对象已释放：写端再写会 EPIPE（没有读端了）
	if _, err := w.Write([]byte("x")); err == nil {
		t.Error("write should fail: the pipe's read side must be released")
	}
	// 编号还被占着，指向 /dev/null；占位由后面的 Close 正常释放
	var st unix.Stat_t
	if err := unix.Fstat(fd, &st); err != nil {
		t.Fatalf("fd %d should stay open as a placeholder: %v", fd, err)
	}
	if st.Mode&unix.S_IFMT != unix.S_IFCHR {
		t.Errorf("placeholder mode = %o, want a character device", st.Mode)
	}
	if err := unix.Close(fd); err != nil {
		t.Errorf("closing the placeholder: %v", err)
	}
}

func TestIsTunLink(t *testing.T) {
	for target, want := range map[string]bool{
		"/dev/tun": true, "/dev/net/tun": true,
		"pipe:[1234]": false, "socket:[99]": false, "/dev/null": false, "": false,
	} {
		if got := isTunLink(target); got != want {
			t.Errorf("isTunLink(%q) = %v, want %v", target, got, want)
		}
	}
}

func TestReleaseTunFdLeavesNonTunAlone(t *testing.T) {
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	defer w.Close()
	releaseTunFd(int(r.Fd())) // 不是 TUN（也可能在没有 /proc 的主机上读不到链接）：必须原样保留
	if _, err := w.Write([]byte("x")); err != nil {
		t.Errorf("a non-tun fd must not be touched: %v", err)
	}
}
