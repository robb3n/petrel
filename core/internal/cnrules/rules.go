// Package cnrules implements the offline, shared checks for bundled DNS rules.
package cnrules

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/metacubex/mihomo/component/trie"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/rules/provider"
)

const MRSFile = "cn-domains.mrs"
const ManifestFile = "cn-domains.sources.json"
const Transform = "union-sort-domain-v1; remove all single-label patterns; reject unsupported syntax"

type Source struct {
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
}
type Manifest struct {
	Schema     int      `json:"schema,omitempty"`
	Created    string   `json:"created"`
	Revision   string   `json:"revision,omitempty"`
	Sources    []Source `json:"sources"`
	Transform  string   `json:"transform"`
	Updates    string   `json:"updates"`
	MRSSHA256  string   `json:"mrs_sha256"`
	Entries    int      `json:"entries,omitempty"`
	RemovedTLD []string `json:"removed_tld,omitempty"`
	Compiler   string   `json:"compiler,omitempty"`
}

func Hash(b []byte) string { return fmt.Sprintf("%x", sha256.Sum256(b)) }
func Text(entries []string) []byte {
	if len(entries) == 0 {
		return nil
	}
	return []byte(strings.Join(entries, "\n") + "\n")
}

// Restrict to auditable domain patterns; never silently discard malformed lines
// as the upstream converter would. Broader matching syntax requires a deliberate
// policy change instead of becoming domestic DNS by surprise.
func Filter(inputs ...[]byte) (entries, removed []string, err error) {
	keep, drop := map[string]bool{}, map[string]bool{}
	for _, data := range inputs {
		scan := bufio.NewScanner(bytes.NewReader(data))
		scan.Buffer(make([]byte, 4096), 64*1024)
		for scan.Scan() {
			line := strings.TrimSpace(scan.Text())
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			base := line
			for _, prefix := range []string{"+.", "*.", "."} {
				if strings.HasPrefix(base, prefix) {
					base = strings.TrimPrefix(base, prefix)
					break
				}
			}
			if base == "" || len(base) > 253 {
				return nil, nil, fmt.Errorf("invalid domain pattern %q", line)
			}
			for _, label := range strings.Split(base, ".") {
				if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
					return nil, nil, fmt.Errorf("invalid domain pattern %q", line)
				}
				for _, c := range label {
					if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
						return nil, nil, fmt.Errorf("unsupported domain pattern %q", line)
					}
				}
			}
			if _, e := trie.ValidAndSplitDomain(line); e != nil {
				return nil, nil, fmt.Errorf("invalid domain pattern: %w", e)
			}
			if !strings.Contains(base, ".") {
				drop[line] = true
				continue
			}
			keep[line] = true
		}
		if err := scan.Err(); err != nil {
			return nil, nil, err
		}
	}
	for k := range keep {
		entries = append(entries, k)
	}
	for k := range drop {
		removed = append(removed, k)
	}
	slices.Sort(entries)
	slices.Sort(removed)
	if len(entries) == 0 {
		return nil, nil, fmt.Errorf("empty domestic rules")
	}
	return entries, removed, nil
}
func Encode(entries []string) ([]byte, error) {
	var b bytes.Buffer
	err := provider.ConvertToMrs(Text(entries), P.Domain, P.TextRule, &b)
	return b.Bytes(), err
}
func Decode(mrs []byte) ([]string, error) {
	var b bytes.Buffer
	if err := provider.ConvertToMrs(mrs, P.Domain, P.MrsRule, &b); err != nil {
		return nil, err
	}
	return strings.Split(strings.TrimSpace(b.String()), "\n"), nil
}
func Verify(mrs, manifest []byte) (Manifest, []string, error) {
	var m Manifest
	if err := json.Unmarshal(manifest, &m); err != nil {
		return m, nil, err
	}
	if m.Schema != 0 && m.Schema != 1 {
		return m, nil, fmt.Errorf("unknown manifest schema")
	}
	if m.Schema == 1 {
		rev, e := hex.DecodeString(m.Revision)
		if e != nil || len(rev) != 20 || m.Entries <= 0 || m.Transform != Transform || m.Compiler == "" || len(m.Sources) != 2 {
			return m, nil, fmt.Errorf("incomplete pinned provenance")
		}
		if _, e = time.Parse(time.RFC3339, m.Created); e != nil {
			return m, nil, fmt.Errorf("invalid source commit date")
		}
		for i, name := range []string{"cn.list", "geolocation-cn.list"} {
			src := m.Sources[i]
			digest, e := hex.DecodeString(src.SHA256)
			if e != nil || len(digest) != 32 || src.URL != "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/"+m.Revision+"/geo/geosite/"+name {
				return m, nil, fmt.Errorf("invalid pinned source metadata")
			}
		}
	}
	if Hash(mrs) != m.MRSSHA256 {
		return m, nil, fmt.Errorf("MRS hash does not match manifest")
	}
	entries, err := Decode(mrs)
	if err != nil {
		return m, nil, err
	}
	_, removed, err := Filter(Text(entries))
	if err != nil {
		return m, nil, err
	}
	if len(removed) != 0 {
		return m, nil, fmt.Errorf("TLD-wide rules found: %v", removed)
	}
	if m.Entries != 0 && m.Entries != len(entries) {
		return m, nil, fmt.Errorf("entry count mismatch")
	}
	// Test actual mihomo matching, not merely whether strings appear in the list.
	strategy := provider.NewDomainStrategy()
	strategy.Reset()
	for _, entry := range entries {
		strategy.Insert(entry)
	}
	strategy.FinishInsert()
	for host, want := range map[string]bool{
		"www.baidu.com": true, "www.bilibili.com": true, "www.taobao.com": true, "www.jd.com": true,
		"www.google.com": false, "www.youtube.com": false, "unclassified-example.cn": false, "unclassified-example.com": false,
	} {
		if strategy.Match(&C.Metadata{Host: host}, C.RuleMatchHelper{}) != want {
			return m, nil, fmt.Errorf("routing sentinel failed: %s", host)
		}
	}
	return m, entries, nil
}
func Diff(before, after []string) (added, removed []string) {
	a, b := map[string]bool{}, map[string]bool{}
	for _, x := range before {
		a[x] = true
	}
	for _, x := range after {
		b[x] = true
	}
	for _, x := range after {
		if !a[x] {
			added = append(added, x)
		}
	}
	for _, x := range before {
		if !b[x] {
			removed = append(removed, x)
		}
	}
	slices.Sort(added)
	slices.Sort(removed)
	return
}
