package ptcore

import (
	"bytes"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	R "github.com/robb3n/petrel/core/internal/cnrules"
	"go.yaml.in/yaml/v3"
)

func TestManagedDNSReplacesUnsafeUpstreams(t *testing.T) {
	input := validMinimal + `
hosts: {nas.lan: 192.168.1.7}
dns:
  enable: false
  nameserver: [system, 114.114.114.114]
  fallback: [8.8.8.8]
  fallback-filter: {geoip: true}
  nameserver-policy: {"+.com": system}
  proxy-server-nameserver-policy: {"+.net": system}
  direct-nameserver: [system]
  fake-ip-filter: ["+.lan", "+.ts.net"]
  fake-ip-filter-mode: blacklist
  ipv6: false
`
	saved := []byte(input)
	out, _, _, err := injectConfig(saved, 42, placeholderSocks, "test", "test")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(saved, []byte(input)) {
		t.Fatal("input mutated")
	}
	var cfg map[string]any
	if err := yaml.Unmarshal(out, &cfg); err != nil {
		t.Fatal(err)
	}
	d := cfg["dns"].(map[string]any)
	for _, k := range []string{"fallback", "fallback-filter", "direct-nameserver", "proxy-server-nameserver-policy", "listen"} {
		if _, ok := d[k]; ok {
			t.Fatalf("unsafe/legacy DNS key retained: %s", k)
		}
	}
	if d["enable"] != true || d["respect-rules"] != false || d["ipv6"] != false {
		t.Fatalf("DNS flags: %v", d)
	}
	if !reflect.DeepEqual(d["nameserver"], []any{"https://1.1.1.1/dns-query#PROXY", "https://8.8.8.8/dns-query#PROXY"}) {
		t.Fatal(d["nameserver"])
	}
	if !reflect.DeepEqual(d["fake-ip-filter"], []any{"+.lan", "+.ts.net"}) || cfg["hosts"] == nil {
		t.Fatal("private hosts/filter lost")
	}
	p := d["nameserver-policy"].(map[string]any)
	if len(p) != 1+len(domesticAppHosts) || p["rule-set:"+cnProvider] == nil {
		t.Fatal("legacy DNS policy leaked through")
	}
}

func TestManagedDNSRoutingAndExplicitExceptions(t *testing.T) {
	cfg, _ := inject(t, strings.Replace(validMinimal, "  - MATCH,PROXY", `  - DOMAIN-SUFFIX,downloads.example,DIRECT
  - DOMAIN,forced.example,PROXY
  - IP-CIDR,100.64.0.0/10,ts,no-resolve
  - GEOIP,CN,DIRECT
  - MATCH,PROXY`, 1))
	r := cfg["rules"].([]any)
	if len(r) != 6+len(domesticAppHosts) || r[3+len(domesticAppHosts)] != "RULE-SET,"+cnProvider+",DIRECT" || r[4+len(domesticAppHosts)] != "GEOIP,CN,DIRECT" {
		t.Fatal(r)
	}
	p := cfg["dns"].(map[string]any)["nameserver-policy"].(map[string]any)
	if !reflect.DeepEqual(p["+.downloads.example"], []any{directDoH}) {
		t.Fatal("direct exception not encrypted/direct")
	}
	if !reflect.DeepEqual(p["forced.example"], []any{"https://1.1.1.1/dns-query#PROXY", "https://8.8.8.8/dns-query#PROXY"}) {
		t.Fatal("proxy exception lost")
	}
}

func TestManagedDNSAutoTargetAndOverride(t *testing.T) {
	for _, name := range []string{"日本 出口", "主代理"} {
		src := strings.ReplaceAll(validMinimal, "PROXY", name)
		cfg, _ := inject(t, src)
		u, err := url.Parse(cfg["dns"].(map[string]any)["nameserver"].([]any)[0].(string))
		if err != nil || u.Fragment != name {
			t.Fatalf("invalid target URL %v %v", u, err)
		}
		if got := ValidateConfig(t.TempDir(), []byte(src)); got != "" {
			t.Fatal(got)
		}
	}
	src := validMinimal + "\npetrel-dns: {proxy: Backup}\n"
	if got := ValidateConfig(t.TempDir(), []byte(src)); !strings.Contains(got, "existing proxy") {
		t.Fatal(got)
	}
	ambiguous := `proxy-groups:
  - {name: A, type: select, proxies: [DIRECT]}
  - {name: B, type: select, proxies: [DIRECT]}
`
	if got := ValidateConfig(t.TempDir(), []byte(ambiguous)); !strings.Contains(got, "petrel-dns.proxy") {
		t.Fatal(got)
	}
	if got := ValidateConfig(t.TempDir(), []byte(ambiguous+"petrel-dns: {proxy: B}\n")); got != "" {
		t.Fatal(got)
	}
}

func TestManagedDNSWeChatImageCompatibility(t *testing.T) {
	for _, tc := range []struct{ name, rule, want string }{
		{"default", "", "DIRECT"},
		{"explicit exact proxy", "DOMAIN,mmbiz.qpic.cn,PROXY", "PROXY"},
		{"explicit suffix proxy", "DOMAIN-SUFFIX,qpic.cn,PROXY", "PROXY"},
		{"explicit reject", "DOMAIN,mmbiz.qpic.cn,REJECT", "REJECT"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			src := validMinimal
			if tc.rule != "" {
				src = strings.Replace(src, "  - MATCH,PROXY", "  - "+tc.rule+"\n  - MATCH,PROXY", 1)
			}
			out, _, _, err := injectConfig([]byte(src), 42, placeholderSocks, "test", "test")
			if err != nil {
				t.Fatal(err)
			}
			cfg, err := parseConfig(t.TempDir(), out)
			if err != nil {
				t.Fatal(err)
			}
			for _, provider := range cfg.RuleProviders {
				if err := provider.Initial(); err != nil {
					t.Fatal(err)
				}
			}
			for host, want := range map[string]string{
				"mmbiz.qpic.cn": tc.want, "mmbiz.qlogo.cn": "DIRECT",
				"unrelated.qlogo.cn": "PROXY", "mmbiz.qpic.cn.example.com": "PROXY",
				"unclassified-example.cn": "PROXY", "www.google.com": "PROXY",
			} {
				got := ""
				for _, rule := range cfg.Rules {
					if ok, target := rule.Match(&C.Metadata{Host: host}, C.RuleMatchHelper{}); ok {
						got = target
						break
					}
				}
				if got != want {
					t.Errorf("route %s: got %s want %s", host, got, want)
				}
			}
			for _, host := range []string{"mmbiz.qpic.cn", "mmbiz.qlogo.cn"} {
				want := "DIRECT"
				if host == "mmbiz.qpic.cn" && tc.want != "DIRECT" {
					want = "PROXY"
				}
				found := false
				for _, policy := range cfg.DNS.NameServerPolicy {
					if policy.Domain != host {
						continue
					}
					found = true
					for _, ns := range policy.NameServers {
						if ns.ProxyName != want {
							t.Errorf("DNS %s leaked to %s, want %s", host, ns.ProxyName, want)
						}
					}
				}
				if !found {
					t.Fatalf("missing DNS policy for %s", host)
				}
			}
		})
	}
}

func TestManagedDNSExceptionsPrecedeCNAndOverlapStaysProtected(t *testing.T) {
	src := strings.Replace(validMinimal, "  - MATCH,PROXY", `  - DOMAIN-SUFFIX,example.com,PROXY
  - DOMAIN,private.example.com,DIRECT
  - DOMAIN,www.baidu.com,PROXY
  - MATCH,PROXY`, 1)
	out, _, _, err := injectConfig([]byte(src), 42, placeholderSocks, "test", "test")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := parseConfig(t.TempDir(), out)
	if err != nil {
		t.Fatal(err)
	}
	p := cfg.DNS.NameServerPolicy
	if len(p) != 4+len(domesticAppHosts) || p[len(p)-1].Matcher == nil {
		t.Fatal("CN matcher must follow explicit domains")
	}
	for _, policy := range p[:len(p)-1] {
		if policy.Domain == "mmbiz.qpic.cn" || policy.Domain == "mmbiz.qlogo.cn" {
			continue
		}
		for _, server := range policy.NameServers {
			if server.ProxyName != "PROXY" {
				t.Fatal("conflicting domain leaked to DIRECT DNS")
			}
		}
	}
}

func TestManagedDNSOriginalModeAndReservedName(t *testing.T) {
	cfg, _ := inject(t, validMinimal+"\npetrel-dns: {mode: original}\ndns: {nameserver: [10.0.0.53]}\n")
	if cfg[dnsPolicyKey] != nil || cfg["rule-providers"] != nil || len(cfg["rules"].([]any)) != 1 {
		t.Fatal("original mode changed routing")
	}
	if !reflect.DeepEqual(cfg["dns"], map[string]any{"nameserver": []any{"10.0.0.53"}}) {
		t.Fatal("original DNS changed")
	}
	for _, extra := range []string{"petrel-dns: false", "petrel-dns: {mode: typo}", "petrel-dns: {porxy: B}", "rule-providers: {petrel-cn-domains: {type: http}}"} {
		if got := ValidateConfig(t.TempDir(), []byte(validMinimal+"\n"+extra)); got == "" {
			t.Fatalf("invalid policy accepted: %s", extra)
		}
	}
}

func TestManagedDNSBundledRulesAndRepair(t *testing.T) {
	manifest, err := os.ReadFile("assets/cn-domains.sources.json")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := R.Verify(cnRules, manifest); err != nil {
		t.Fatal(err)
	}
	if cnRulePath != "rules/petrel-cn-domains-"+R.Hash(cnRules)[:8]+".mrs" {
		t.Fatal("snapshot path is stale")
	}
	home := t.TempDir()
	injected, _, _, err := injectConfig([]byte(validMinimal), 42, placeholderSocks, "test", "test")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := parseConfig(home, injected)
	if err != nil {
		t.Fatal(err)
	}
	p := cfg.RuleProviders[cnProvider]
	if err := p.Initial(); err != nil {
		t.Fatal(err)
	}
	for host, want := range map[string]bool{"www.baidu.com": true, "www.bilibili.com": true, "www.taobao.com": true, "www.google.com": false, "unclassified-example.cn": false, "unclassified-example.com": false} {
		if got := p.Match(&C.Metadata{Host: host}, C.RuleMatchHelper{}); got != want {
			t.Errorf("%s: %v != %v", host, got, want)
		}
	}
	// Corrupt/missing resource is repaired without touching the imported YAML.
	path := filepath.Join(home, cnRulePath)
	if err := os.WriteFile(path, []byte("partial"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ensureDNSRules(home); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(b, cnRules) {
		t.Fatal("resource not restored")
	}
}

type cacheSpy struct {
	resolver.Resolver
	cleared int
}

func (s *cacheSpy) ClearCache() { s.cleared++ }

func TestNodeSwitchClearsDNSCacheSynchronously(t *testing.T) {
	old, sys := resolver.DefaultResolver, resolver.SystemResolver
	a, b := &cacheSpy{}, &cacheSpy{}
	resolver.DefaultResolver, resolver.SystemResolver = a, b
	defer func() { resolver.DefaultResolver, resolver.SystemResolver = old, sys }()
	clearDNSCache()
	if a.cleared != 1 || b.cleared != 1 {
		t.Fatal("DNS cache still stale after selection returns")
	}
}

// "RULES" is the DNS dialer's keyword for "follow the routing rules". An outbound
// with that name must never become the protected DNS proxy, however it is chosen.
func TestManagedDNSRejectsReservedRulesName(t *testing.T) {
	named := strings.ReplaceAll(validMinimal, "PROXY", "RULES")
	// Inferred from the only group / the MATCH target.
	if got := ValidateConfig(t.TempDir(), []byte(named)); !strings.Contains(got, "无法确定 DNS 的主代理") {
		t.Fatalf("inferred RULES accepted: %q", got)
	}
	// Explicitly requested.
	if got := ValidateConfig(t.TempDir(), []byte(named+"\npetrel-dns: {proxy: RULES}\n")); !strings.Contains(got, "petrel-dns.proxy") {
		t.Fatalf("explicit RULES accepted: %q", got)
	}
	// Next to a usable group it is passed over rather than picked.
	both := `proxy-groups:
  - {name: RULES, type: select, proxies: [DIRECT]}
  - {name: Main, type: select, proxies: [DIRECT]}
rules:
  - MATCH,RULES
`
	if got := ValidateConfig(t.TempDir(), []byte(both)); !strings.Contains(got, "无法确定 DNS 的主代理") {
		t.Fatalf("MATCH,RULES accepted: %q", got)
	}
	cfg, _ := inject(t, both+"petrel-dns: {proxy: Main}\n")
	if got := cfg["dns"].(map[string]any)["nameserver"].([]any)[0]; got != "https://1.1.1.1/dns-query#Main" {
		t.Fatal(got)
	}
}

// Mihomo upper-cases rule types, so lower-case rules route traffic and must
// steer DNS, the insertion point and main-proxy inference alike.
func TestManagedDNSLowerCaseRuleTypes(t *testing.T) {
	src := `proxy-groups:
  - {name: Main, type: select, proxies: [DIRECT]}
  - {name: Other, type: select, proxies: [DIRECT]}
rules:
  - domain-suffix,baidu.com,Main
  - Domain,direct.example,DIRECT
  - match,Main
`
	cfg, _ := inject(t, src)
	dns := cfg["dns"].(map[string]any)
	proxied := []any{"https://1.1.1.1/dns-query#Main", "https://8.8.8.8/dns-query#Main"}
	if !reflect.DeepEqual(dns["nameserver"], proxied) {
		t.Fatalf("main proxy not inferred from a lower-case match: %v", dns["nameserver"])
	}
	policy := dns["nameserver-policy"].(map[string]any)
	if !reflect.DeepEqual(policy["+.baidu.com"], proxied) {
		t.Fatalf("lower-case proxy rule missing from DNS policy: %v", policy["+.baidu.com"])
	}
	if !reflect.DeepEqual(policy["direct.example"], []any{directDoH}) {
		t.Fatalf("mixed-case direct rule missing from DNS policy: %v", policy["direct.example"])
	}
	rules := cfg["rules"].([]any)
	if rules[len(rules)-1] != "match,Main" || rules[len(rules)-2] != "RULE-SET,"+cnProvider+",DIRECT" {
		t.Fatalf("domestic rule not ahead of the lower-case catch-all: %v", rules)
	}
	withGeo, _ := inject(t, strings.Replace(src, "  - match,Main", "  - geoip,cn,DIRECT\n  - match,Main", 1))
	rules = withGeo["rules"].([]any)
	if rules[len(rules)-2] != "geoip,cn,DIRECT" || rules[len(rules)-3] != "RULE-SET,"+cnProvider+",DIRECT" {
		t.Fatalf("domestic rule not ahead of lower-case geoip: %v", rules)
	}

	// The real matchers agree: the explicit rule wins over the domestic set.
	out, _, _, err := injectConfig([]byte(src), 42, placeholderSocks, "test", "test")
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := parseConfig(t.TempDir(), out)
	if err != nil {
		t.Fatal(err)
	}
	for _, provider := range parsed.RuleProviders {
		if err := provider.Initial(); err != nil {
			t.Fatal(err)
		}
	}
	// (A RULE-SET rule resolves its provider through the running tunnel, so the
	// domestic set itself is not matched here; its position is asserted above.)
	for host, want := range map[string]string{"www.baidu.com": "Main", "direct.example": "DIRECT", "www.google.com": "Main"} {
		got := ""
		for _, rule := range parsed.Rules {
			if ok, target := rule.Match(&C.Metadata{Host: host}, C.RuleMatchHelper{}); ok {
				got = target
				break
			}
		}
		if got != want {
			t.Errorf("route %s: got %s want %s", host, got, want)
		}
	}
	for _, p := range parsed.DNS.NameServerPolicy {
		if p.Domain == "+.baidu.com" {
			for _, ns := range p.NameServers {
				if ns.ProxyName != "Main" {
					t.Errorf("baidu.com DNS leaked to %s", ns.ProxyName)
				}
			}
		}
	}
}

// A routing rule takes payloads a nameserver-policy key cannot: those rules
// keep routing, and the configuration still loads.
func TestManagedDNSSkipsPayloadsInvalidAsPolicyKeys(t *testing.T) {
	bad := []string{
		"DOMAIN-SUFFIX,.dotted.example,PROXY",
		"DOMAIN-SUFFIX,trailing.example.,DIRECT",
		"DOMAIN,trailing-exact.example.,PROXY",
		"DOMAIN,rule-set:nowhere,PROXY",
		"DOMAIN,GeoSite:cn,DIRECT",
	}
	src := strings.Replace(validMinimal, "  - MATCH,PROXY",
		"  - "+strings.Join(bad, "\n  - ")+"\n  - DOMAIN,kept.example,PROXY\n  - MATCH,PROXY", 1)
	if got := ValidateConfig(t.TempDir(), []byte(src)); got != "" {
		t.Fatalf("configuration that loaded before managed DNS is rejected: %s", got)
	}
	cfg, _ := inject(t, src)
	policy := cfg["dns"].(map[string]any)["nameserver-policy"].(map[string]any)
	if len(policy) != 2+len(domesticAppHosts) || policy["kept.example"] == nil || policy["rule-set:"+cnProvider] == nil {
		t.Fatalf("unexpected policy keys: %v", policy)
	}
	rules := cfg["rules"].([]any)
	for i, want := range bad {
		if rules[i] != want {
			t.Fatalf("routing rule %d changed: %v", i, rules[i])
		}
	}
}

func TestManagedDNSPrunesSupersededSnapshots(t *testing.T) {
	home := t.TempDir()
	dir := filepath.Join(home, "rules")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	stale := []string{cnProvider + "-00000000.mrs", cnProvider + "-ffffffff.mrs"}
	keep := []string{"user-rules.mrs", cnProvider + ".yaml", cnProvider + "-notes.txt"}
	for _, n := range append(append([]string{}, stale...), keep...) {
		if err := os.WriteFile(filepath.Join(dir, n), []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	// A leftover that cannot be removed (a non-empty directory) must not fail the start.
	stuck := filepath.Join(dir, cnProvider+"-stuck.mrs")
	if err := os.MkdirAll(filepath.Join(stuck, "x"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := ensureDNSRules(home); err != nil {
		t.Fatal(err)
	}
	if b, err := os.ReadFile(filepath.Join(home, cnRulePath)); err != nil || !bytes.Equal(b, cnRules) {
		t.Fatal("current snapshot missing")
	}
	for _, n := range stale {
		if _, err := os.Stat(filepath.Join(dir, n)); !os.IsNotExist(err) {
			t.Errorf("superseded snapshot kept: %s", n)
		}
	}
	for _, n := range keep {
		if _, err := os.Stat(filepath.Join(dir, n)); err != nil {
			t.Errorf("unrelated file removed: %s", n)
		}
	}
	// Also when the current snapshot was already installed.
	late := filepath.Join(dir, stale[0])
	if err := os.WriteFile(late, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ensureDNSRules(home); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(late); !os.IsNotExist(err) {
		t.Error("superseded snapshot kept on the already-installed path")
	}
}

// Managed rules need rule mode; an explicit global/direct is refused instead of
// being silently rewritten.
func TestManagedDNSRejectsGlobalAndDirectMode(t *testing.T) {
	for _, mode := range []string{"global", "GLOBAL", "direct", "Direct"} {
		got := ValidateConfig(t.TempDir(), []byte("mode: "+mode+"\n"+validMinimal))
		if !strings.Contains(got, "mode: "+mode) || !strings.Contains(got, "petrel-dns.mode: original") {
			t.Errorf("mode %s: %q", mode, got)
		}
		// The escape hatch keeps the user's mode untouched.
		cfg, _ := inject(t, "mode: "+mode+"\n"+validMinimal+"petrel-dns: {mode: original}\n")
		if cfg["mode"] != mode {
			t.Errorf("original mode rewrote mode %s to %v", mode, cfg["mode"])
		}
	}
	for _, prefix := range []string{"", "mode: rule\n", "mode: Rule\n"} {
		cfg, _ := inject(t, prefix+validMinimal)
		if cfg["mode"] != "rule" {
			t.Errorf("%q: mode = %v", prefix, cfg["mode"])
		}
	}
	// Nothing to manage (no Internet proxy): the mode is left alone.
	if cfg, _ := inject(t, "mode: global\n"); cfg["mode"] != "global" || cfg["dns"] != nil {
		t.Errorf("empty configuration was managed: %v", cfg)
	}
}
