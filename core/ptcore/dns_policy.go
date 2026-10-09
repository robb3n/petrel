package ptcore

import (
	"bytes"
	"crypto/sha256"
	_ "embed"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"go.yaml.in/yaml/v3"
)

const (
	dnsPolicyKey = "petrel-dns"
	cnProvider   = "petrel-cn-domains"
	directDoH    = "https://223.5.5.5/dns-query#DIRECT"
)

// Fixed, filtered snapshot: provenance and update instructions are beside the asset.
//
//go:embed assets/cn-domains.mrs
var cnRules []byte

// Derive the content-addressed path; updates no longer require editing Go literals.
var cnRulePath = "rules/petrel-cn-domains-" + fmt.Sprintf("%x", sha256.Sum256(cnRules))[:8] + ".mrs"

// Audited app endpoints missing from the upstream geographic snapshot. Keep
// exact hosts: a shared CDN suffix is not automatically a domestic service.
// This single list supplies both DNS policy and connection routing. Explicit
// user rules retain priority; see docs/lessons/dns-policy.md for evidence.
var domesticAppHosts = [...]string{"mmbiz.qpic.cn", "mmbiz.qlogo.cn"}

// applyDNSPolicy runs for both validation and startup, on a freshly decoded copy.
// The imported file is never rewritten. Original mode is an explicit escape hatch
// for split-horizon/private DNS or configurations that need their own DNS policy.
func applyDNSPolicy(cfg map[string]any) error {
	var requested string
	if raw, exists := cfg[dnsPolicyKey]; exists {
		p, ok := raw.(map[string]any)
		if !ok {
			return fmt.Errorf("petrel-dns: expected mode/proxy map")
		}
		for k := range p {
			if k != "mode" && k != "proxy" {
				return fmt.Errorf("petrel-dns: unknown option %q", k)
			}
		}
		mode, _ := p["mode"].(string)
		if v, exists := p["mode"]; exists && v != "managed" && v != "original" {
			return fmt.Errorf("petrel-dns.mode: use managed or original")
		}
		delete(cfg, dnsPolicyKey)
		if mode == "original" {
			return nil
		}
		if mode != "" && mode != "managed" {
			return fmt.Errorf("petrel-dns.mode: use managed or original")
		}
		if v, exists := p["proxy"]; exists {
			requested, ok = v.(string)
			if !ok || strings.TrimSpace(requested) == "" {
				return fmt.Errorf("petrel-dns.proxy: expected an outbound name")
			}
		}
	}
	proxy, err := dnsProxy(cfg, requested)
	if err != nil {
		return err
	}
	if proxy == "" {
		return nil
	} // empty / tailnet-only config has no Internet proxy
	cfg["mode"] = "rule"

	providers, _ := cfg["rule-providers"].(map[string]any)
	if providers == nil {
		if cfg["rule-providers"] != nil {
			return fmt.Errorf("rule-providers: expected a map")
		}
		providers = map[string]any{}
	}
	if _, exists := providers[cnProvider]; exists {
		return fmt.Errorf("rule-providers: %s is reserved by Petrel", cnProvider)
	}
	providers[cnProvider] = map[string]any{"type": "file", "behavior": "domain", "format": "mrs", "path": cnRulePath}
	cfg["rule-providers"] = providers

	// Keep address-family and fake-IP compatibility settings, but replace every
	// upstream/policy/fallback: merging untrusted DNS entries can reintroduce leaks.
	old, _ := cfg["dns"].(map[string]any)
	dns := map[string]any{
		"enable": true, "enhanced-mode": "fake-ip", "fake-ip-range": "198.18.0.1/16",
		"respect-rules": false, "use-hosts": true, "use-system-hosts": false, "prefer-h3": false,
		"default-nameserver":      []any{"https://223.5.5.5/dns-query"},
		"proxy-server-nameserver": []any{directDoH},
		"nameserver":              []any{dohVia("https://1.1.1.1/dns-query", proxy), dohVia("https://8.8.8.8/dns-query", proxy)},
	}
	for _, k := range []string{"ipv6", "fake-ip-range", "fake-ip-range6", "fake-ip-filter", "fake-ip-filter-mode", "fake-ip-ttl"} {
		if v, ok := old[k]; ok {
			dns[k] = v
		}
	}
	if cfg["ipv6"] == false {
		dns["ipv6"] = false
	}
	policy := map[string]any{}
	protected := map[string]bool{}
	direct := map[string]bool{}
	for _, host := range domesticAppHosts {
		policy[host] = []any{directDoH}
		direct[host] = true
	}
	rules, ok := cfg["rules"].([]any)
	if !ok && cfg["rules"] != nil {
		return fmt.Errorf("rules: expected a list")
	}
	// Existing explicit domain rules also guide DNS. This preserves explicit
	// DIRECT downloads and proxy exceptions instead of carrying over raw resolvers.
	// Complex rules still govern traffic; unknown domains use protected DNS.
	for i := len(rules) - 1; i >= 0; i-- {
		r, _ := rules[i].(string)
		parts := strings.Split(r, ",")
		if len(parts) != 3 {
			continue
		}
		for j := range parts {
			parts[j] = strings.TrimSpace(parts[j])
		}
		var key string
		switch parts[0] {
		case "DOMAIN":
			key = parts[1]
		case "DOMAIN-SUFFIX":
			key = "+." + parts[1]
		default:
			continue
		}
		if parts[2] == "DIRECT" {
			policy[key] = []any{directDoH}
			direct[key] = true
		} else {
			policy[key] = dns["nameserver"]
			protected[key] = true
		}
	}
	// DNS uses longest-domain matching, whereas traffic uses first rule wins.
	// On overlap, conservatively proxy the whole exception rather than allowing
	// a more-specific DIRECT DNS entry to expose a domain routed through a proxy.
	for key := range direct {
		for p := range protected {
			if dnsDomainsOverlap(key, p) {
				policy[key] = dns["nameserver"]
				break
			}
		}
	}
	// A Go map is marshalled in lexical order. "www..." would land AFTER the
	// rule-set matcher and lose to CN. Encode explicit domains before the set.
	node := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
	keys := make([]string, 0, len(policy))
	for k := range policy {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	keys = append(keys, "rule-set:"+cnProvider)
	policy["rule-set:"+cnProvider] = []any{directDoH}
	for _, k := range keys {
		value := &yaml.Node{}
		if err := value.Encode(policy[k]); err != nil {
			return err
		}
		node.Content = append(node.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: k}, value)
	}
	dns["nameserver-policy"] = node
	cfg["dns"] = dns

	// Explicit rules retain priority. Add the domestic domain rule ahead of
	// GEOIP/CN and the final catch-all, avoiding a proxy DNS round trip first.
	insert := len(rules)
	for i, raw := range rules {
		r, _ := raw.(string)
		p := strings.Split(r, ",")
		if len(p) > 0 && (strings.TrimSpace(p[0]) == "MATCH" || strings.TrimSpace(p[0]) == "FINAL" ||
			(len(p) > 1 && strings.TrimSpace(p[0]) == "GEOIP" && strings.EqualFold(strings.TrimSpace(p[1]), "CN"))) {
			insert = i
			break
		}
	}
	merged := append([]any{}, rules[:insert]...)
	for _, host := range domesticAppHosts {
		merged = append(merged, "DOMAIN,"+host+",DIRECT")
	}
	merged = append(merged, "RULE-SET,"+cnProvider+",DIRECT")
	cfg["rules"] = append(merged, rules[insert:]...)
	return nil
}

func dnsDomainsOverlap(a, b string) bool {
	a, b = strings.ToLower(a), strings.ToLower(b)
	x, y := strings.TrimPrefix(a, "+."), strings.TrimPrefix(b, "+.")
	return x == y || (strings.HasPrefix(a, "+.") && strings.HasSuffix(y, "."+x)) ||
		(strings.HasPrefix(b, "+.") && strings.HasSuffix(x, "."+y))
}

func dohVia(endpoint, proxy string) string {
	u, _ := url.Parse(endpoint)
	u.Fragment = proxy
	return u.String()
}

func dnsProxy(cfg map[string]any, requested string) (string, error) {
	names := map[string]bool{}
	groups := groupNamesIn(cfg)
	for _, n := range groups {
		names[n] = true
	}
	proxies, _ := cfg["proxies"].([]any)
	for _, raw := range proxies {
		if p, ok := raw.(map[string]any); ok {
			if n, ok := p["name"].(string); ok && n != tsProxyName {
				names[n] = true
			}
		}
	}
	valid := func(n string) bool {
		if !names[n] || n == tsProxyName {
			return false
		}
		for _, b := range builtinProxyNames {
			if n == b {
				return false
			}
		}
		// Mihomo separates DNS fragment options with '&'. Reject ambiguous names.
		return !strings.ContainsAny(n, "&#=")
	}
	if requested != "" {
		if !valid(requested) {
			return "", fmt.Errorf("petrel-dns.proxy: choose an existing proxy/group (not DIRECT, ts or a name containing &/#/=)")
		}
		return requested, nil
	}
	if valid("PROXY") {
		return "PROXY", nil
	}
	if rules, ok := cfg["rules"].([]any); ok {
		for _, raw := range rules {
			r, _ := raw.(string)
			p := strings.Split(r, ",")
			if len(p) == 2 && (strings.TrimSpace(p[0]) == "MATCH" || strings.TrimSpace(p[0]) == "FINAL") && valid(strings.TrimSpace(p[1])) {
				return strings.TrimSpace(p[1]), nil
			}
		}
	}
	if len(groups) == 1 && valid(groups[0]) {
		return groups[0], nil
	}
	if len(names) == 0 {
		return "", nil
	}
	return "", fmt.Errorf("无法确定 DNS 的主代理：请在配置中设置 petrel-dns.proxy，或用 petrel-dns.mode: original 保留原 DNS")
}

// Called under parseMu. The content-addressed, app-owned snapshot is installed
// atomically and only when needed; a failed import cannot replace user data.
func ensureDNSRules(home string) error {
	p := filepath.Join(home, cnRulePath)
	if b, err := os.ReadFile(p); err == nil && bytes.Equal(b, cnRules) {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(p), ".petrel-cn-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(cnRules); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(f.Name(), p)
}
