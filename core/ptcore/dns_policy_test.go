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
	if len(p) != 1 || p["rule-set:"+cnProvider] == nil {
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
	if len(r) != 6 || r[3] != "RULE-SET,"+cnProvider+",DIRECT" || r[4] != "GEOIP,CN,DIRECT" {
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
	if len(p) != 4 || p[len(p)-1].Matcher == nil {
		t.Fatal("CN matcher must follow explicit domains")
	}
	for _, policy := range p[:len(p)-1] {
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
