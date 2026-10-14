package cnrules

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"slices"
	"strings"
)

// RecoveryPolicy is the reviewed allowlist; new upstream members stay deferred.
//
//go:embed recovery-policy.json
var RecoveryPolicy []byte

type Recovery struct {
	IgnoredServiceTLD []string `json:"ignored_service_tld,omitempty"`
	Deferred          []string `json:"deferred_unreviewed,omitempty"`
	Added             []string `json:"added"`
	Excluded          []string `json:"excluded_overseas_overlap"`
}

// Build recovers explicit service domains lost when a minimized geographic
// source's TLD wildcard is removed. It never restores the wildcard itself, nor
// unions an entire vendor category (which also contains international services).
func Build(schema int, inputs ...[]byte) ([]string, []string, Recovery, error) {
	var report Recovery
	if schema != 1 && schema != 2 && schema != Schema {
		return nil, nil, report, fmt.Errorf("unsupported build schema")
	}
	if len(inputs) != len(SourceNames(schema)) {
		return nil, nil, report, fmt.Errorf("source count mismatch")
	}
	base, dropped, err := Filter(inputs[:2]...)
	if err != nil || schema == 1 {
		return base, dropped, report, err
	}
	serviceInputs, foreignInputs := [][]byte{inputs[2]}, [][]byte{inputs[3]}
	var approved map[string]bool
	if schema == Schema {
		serviceInputs, foreignInputs = nil, nil
		for i := 2; i < len(inputs)-1; i += 2 {
			serviceInputs = append(serviceInputs, inputs[i])
			foreignInputs = append(foreignInputs, inputs[i+1])
		}
		foreignInputs = append(foreignInputs, inputs[len(inputs)-1])
		var policy struct {
			Approved []string `json:"approved"`
		}
		if err := json.Unmarshal(RecoveryPolicy, &policy); err != nil {
			return nil, nil, report, err
		}
		approved = map[string]bool{}
		for _, p := range policy.Approved {
			approved[p] = true
		}
	}
	services, serviceTLDs, err := Filter(serviceInputs...)
	if err != nil {
		return nil, nil, report, err
	}
	foreign, foreignTLDs, err := Filter(foreignInputs...)
	if err != nil {
		return nil, nil, report, err
	}
	if schema == 2 && (len(serviceTLDs) != 0 || len(foreignTLDs) != 0) {
		return nil, nil, report, fmt.Errorf("TLD patterns in recovery categories require review")
	}
	if schema == Schema {
		report.IgnoredServiceTLD = serviceTLDs
	}
	// Foreign TLD rules are deny boundaries, never dropped from the exclusion set.
	foreign = append(foreign, foreignTLDs...)
	// Recovery only interprets exact and domain-suffix patterns. Fail closed if
	// upstream starts expressing wildcard/partial-domain rules in either category.
	for _, patterns := range [][]string{services, foreign} {
		for _, p := range patterns {
			if strings.HasPrefix(p, "*.") || strings.HasPrefix(p, ".") {
				return nil, nil, report, fmt.Errorf("unsupported recovery pattern %q", p)
			}
		}
	}
	for _, p := range services {
		host := strings.ToLower(strings.TrimPrefix(p, "+."))
		underRemovedTLD := false
		for _, tld := range dropped {
			tld = strings.ToLower(strings.TrimLeft(tld, "+*."))
			if strings.HasSuffix(host, "."+tld) {
				underRemovedTLD = true
				break
			}
		}
		if !underRemovedTLD {
			continue
		}
		conflict := false
		for _, f := range foreign {
			if overlap(p, f) {
				conflict = true
				break
			}
		}
		if conflict {
			report.Excluded = append(report.Excluded, p)
			continue
		}
		// Only record actual additions, including subdomain patterns not covered by
		// a surviving base suffix. Exact base entries do not cover a suffix rule.
		covered := false
		for _, b := range base {
			if b == p || (strings.HasPrefix(b, "+.") && contains(b, host)) {
				covered = true
				break
			}
		}
		if !covered && schema == Schema && !approved[p] {
			report.Deferred = append(report.Deferred, p)
			continue
		}
		if !covered {
			report.Added = append(report.Added, p)
		}
	}
	entries, _, err := Filter(Text(base), Text(report.Added))
	slices.Sort(report.Added)
	slices.Sort(report.Excluded)
	slices.Sort(report.Deferred)
	return entries, dropped, report, err
}
func contains(pattern, host string) bool {
	pattern = strings.ToLower(pattern)
	base := strings.TrimPrefix(pattern, "+.")
	return host == base || (strings.HasPrefix(pattern, "+.") && strings.HasSuffix(host, "."+base))
}
func overlap(a, b string) bool {
	return contains(a, strings.ToLower(strings.TrimPrefix(b, "+."))) || contains(b, strings.ToLower(strings.TrimPrefix(a, "+.")))
}
