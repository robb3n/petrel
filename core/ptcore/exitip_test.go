package ptcore

import (
	"testing"

	"github.com/metacubex/mihomo/adapter/provider"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/tunnel"
)

// 组里列出来的成员可能来自 proxy-provider：它们不在 tunnel.Proxies() 里，按名字查出口 IP 时也要找得到。
func TestLookupProxyIncludesProviderMembers(t *testing.T) {
	top, member := dnsTestNode("top"), dnsTestNode("from-provider")
	pd, err := provider.NewCompatibleProvider("sub", []C.Proxy{member}, provider.NewHealthCheck([]C.Proxy{member}, "", 0, 0, true, nil))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pd.Close() })
	oldProxies, oldProviders := tunnel.Proxies(), tunnel.Providers()
	tunnel.UpdateProxies(map[string]C.Proxy{"top": top}, map[string]P.ProxyProvider{"sub": pd})
	t.Cleanup(func() { tunnel.UpdateProxies(oldProxies, oldProviders) })

	if p, ok := lookupProxy("top"); !ok || p.Name() != "top" {
		t.Fatal("top-level proxy not found")
	}
	if p, ok := lookupProxy("from-provider"); !ok || p.Name() != "from-provider" {
		t.Fatal("provider member not found")
	}
	if _, ok := lookupProxy("ghost"); ok {
		t.Fatal("unknown name found")
	}
}
