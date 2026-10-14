// cn-rules maintains the audited, bundled domestic DNS snapshot. No command
// changes a running device, proxy configuration, or the user's imported YAML.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime/debug"
	"slices"
	"time"

	R "github.com/robb3n/petrel/core/internal/cnrules"
)

var sourceNames = R.SourceNames(R.Schema)
var shaPattern = regexp.MustCompile(`^[0-9a-f]{40}$`)

const repo = "https://api.github.com/repos/MetaCubeX/meta-rules-dat/commits/"
const rawRoot = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/"

type bundle struct{ mrs, manifest []byte }

func readBundle(dir string) (bundle, error) {
	a, err := os.ReadFile(filepath.Join(dir, R.MRSFile))
	if err != nil {
		return bundle{}, err
	}
	b, err := os.ReadFile(filepath.Join(dir, R.ManifestFile))
	return bundle{a, b}, err
}
func (b bundle) verify() (R.Manifest, []string, error) { return R.Verify(b.mrs, b.manifest) }
func (b bundle) fingerprint() string {
	return R.Hash(append(append([]byte{}, b.mrs...), b.manifest...))
}
func jsonBytes(v any) []byte {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		panic(err)
	}
	return append(b, '\n')
}
func write(dir, name string, b []byte) error { return os.WriteFile(filepath.Join(dir, name), b, 0o600) }
func saveBundle(dir string, b bundle) error {
	if err := write(dir, R.MRSFile, b.mrs); err != nil {
		return err
	}
	return write(dir, R.ManifestFile, b.manifest)
}
func fetch(client *http.Client, u string) ([]byte, error) {
	req, err := http.NewRequest(http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "petrel-cn-rules")
	req.Header.Set("Accept", "application/vnd.github+json")
	res, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("download %s: HTTP %d", u, res.StatusCode)
	}
	const limit = 16 << 20
	b, err := io.ReadAll(io.LimitReader(res.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if len(b) > limit {
		return nil, fmt.Errorf("source exceeds 16 MiB")
	}
	return b, nil
}
func compiler() string {
	if b, ok := debug.ReadBuildInfo(); ok {
		for _, d := range b.Deps {
			if d.Path == "github.com/metacubex/mihomo" {
				return d.Path + "@" + d.Version
			}
		}
	}
	return "unknown"
}

type report struct {
	Revision  string `json:"revision"`
	Baseline  string `json:"baseline_bundle_sha256"`
	Candidate string `json:"candidate_mrs_sha256"`
	Before    int    `json:"before"`
	After     int    `json:"after"`
	Added     int    `json:"added"`
	Removed   int    `json:"removed"`
	Review    string `json:"review"`
}

func prepare(assets, dir, ref string) error {
	client := &http.Client{Timeout: 45 * time.Second}
	return prepareWithFetch(assets, dir, ref, func(u string) ([]byte, error) { return fetch(client, u) })
}

func prepareWithFetch(assets, dir, ref string, download func(string) ([]byte, error)) error {
	before, err := readBundle(assets)
	if err != nil {
		return err
	}
	_, old, err := before.verify()
	if err != nil {
		return fmt.Errorf("baseline: %w", err)
	}
	if _, err = os.Stat(dir); !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("candidate directory must not exist")
	}
	commit, err := download(repo + url.PathEscape(ref))
	if err != nil {
		return err
	}
	var c struct {
		SHA    string `json:"sha"`
		Commit struct {
			Committer struct {
				Date string `json:"date"`
			} `json:"committer"`
		} `json:"commit"`
	}
	if err = json.Unmarshal(commit, &c); err != nil {
		return err
	}
	if !shaPattern.MatchString(c.SHA) {
		return fmt.Errorf("invalid upstream commit SHA")
	}
	var inputs [][]byte
	var sources []R.Source
	for _, name := range sourceNames {
		u := rawRoot + c.SHA + "/geo/geosite/" + name
		b, e := download(u)
		if e != nil {
			return e
		}
		inputs = append(inputs, b)
		sources = append(sources, R.Source{URL: u, SHA256: R.Hash(b)})
	}
	entries, removed, recovery, err := R.Build(R.Schema, inputs...)
	if err != nil {
		return err
	}
	mrs, err := R.Encode(entries)
	if err != nil {
		return err
	}
	// Compact representation is used for both reports and manifest count.
	compact, err := R.Decode(mrs)
	if err != nil {
		return err
	}
	m := R.Manifest{Schema: R.Schema, Created: c.Commit.Committer.Date, Revision: c.SHA, Sources: sources, Transform: R.Transform,
		Updates: "Pinned audited bundle; prepare, inspect diff, apply exact hash; never refresh on device.", MRSSHA256: R.Hash(mrs), Entries: len(compact), RemovedTLD: removed, Compiler: compiler(), PolicySHA256: R.Hash(R.RecoveryPolicy)}
	candidate := bundle{mrs, jsonBytes(m)}
	if _, _, err = candidate.verify(); err != nil {
		return err
	}
	added, deleted := R.Diff(old, compact)
	r := report{c.SHA, before.fingerprint(), m.MRSSHA256, len(old), len(compact), len(added), len(deleted), "Inspect added.txt and removed.txt; added domestic matches can expose queries to domestic DNS. Counts alone are not approval."}
	// Publish a complete candidate directory or none; a failed download never
	// touches the repository snapshot. Rename on the same filesystem.
	if err = os.MkdirAll(filepath.Dir(dir), 0o700); err != nil {
		return err
	}
	tmp, err := os.MkdirTemp(filepath.Dir(dir), ".cn-rules-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	if err = saveBundle(tmp, candidate); err != nil {
		return err
	}
	for i, name := range sourceNames {
		if err = write(tmp, name, inputs[i]); err != nil {
			return err
		}
	}
	for name, b := range map[string][]byte{"added.txt": R.Text(added), "removed.txt": R.Text(deleted), "report.json": jsonBytes(r), "recovery.json": jsonBytes(recovery), "recovery-policy.json": R.RecoveryPolicy, "baseline.mrs": before.mrs, "baseline.sources.json": before.manifest} {
		if err = write(tmp, name, b); err != nil {
			return err
		}
	}
	if err = os.Rename(tmp, dir); err != nil {
		return err
	}
	fmt.Print(string(jsonBytes(r)))
	return nil
}

// Rebuild candidates offline using all preserved raw sources and review policy. Review files are
// checked too, so the operator cannot inadvertently apply a different delta.
func verifyCandidate(dir string) (bundle, bundle, error) {
	b, err := readBundle(dir)
	if err != nil {
		return b, bundle{}, err
	}
	m, entries, err := b.verify()
	if err != nil {
		return b, bundle{}, err
	}
	if m.Schema != R.Schema || m.Transform != R.Transform || !shaPattern.MatchString(m.Revision) || len(m.Sources) != len(sourceNames) || m.Compiler != compiler() || m.PolicySHA256 != R.Hash(R.RecoveryPolicy) {
		return b, bundle{}, fmt.Errorf("candidate provenance/compiler mismatch")
	}
	var inputs [][]byte
	for i, name := range sourceNames {
		src := m.Sources[i]
		if src.URL != rawRoot+m.Revision+"/geo/geosite/"+name {
			return b, bundle{}, fmt.Errorf("source is not pinned to the candidate revision")
		}
		data, e := os.ReadFile(filepath.Join(dir, name))
		if e != nil {
			return b, bundle{}, e
		}
		if R.Hash(data) != src.SHA256 {
			return b, bundle{}, fmt.Errorf("raw source hash mismatch: %s", name)
		}
		inputs = append(inputs, data)
	}
	filtered, dropped, recovery, err := R.Build(m.Schema, inputs...)
	if err != nil {
		return b, bundle{}, err
	}
	rebuilt, err := R.Encode(filtered)
	if err != nil {
		return b, bundle{}, err
	}
	if !bytes.Equal(rebuilt, b.mrs) || !slices.Equal(dropped, m.RemovedTLD) {
		return b, bundle{}, fmt.Errorf("candidate cannot be reproduced")
	}
	a, err := os.ReadFile(filepath.Join(dir, "baseline.mrs"))
	if err != nil {
		return b, bundle{}, err
	}
	meta, err := os.ReadFile(filepath.Join(dir, "baseline.sources.json"))
	if err != nil {
		return b, bundle{}, err
	}
	before := bundle{a, meta}
	_, old, err := before.verify()
	if err != nil {
		return b, before, err
	}
	added, removed := R.Diff(old, entries)
	var r report
	reportBytes, err := os.ReadFile(filepath.Join(dir, "report.json"))
	if err != nil {
		return b, before, err
	}
	if err = json.Unmarshal(reportBytes, &r); err != nil {
		return b, before, err
	}
	if r.Baseline != before.fingerprint() || r.Candidate != R.Hash(b.mrs) || r.Revision != m.Revision || r.Before != len(old) || r.After != len(entries) || r.Added != len(added) || r.Removed != len(removed) {
		return b, before, fmt.Errorf("candidate report mismatch")
	}
	for name, expected := range map[string][]byte{"added.txt": R.Text(added), "removed.txt": R.Text(removed), "recovery.json": jsonBytes(recovery), "recovery-policy.json": R.RecoveryPolicy} {
		data, e := os.ReadFile(filepath.Join(dir, name))
		if e != nil {
			return b, before, e
		}
		if !bytes.Equal(data, expected) {
			return b, before, fmt.Errorf("review diff changed: %s", name)
		}
	}
	return b, before, nil
}

// Each individual file is replaced atomically. On ordinary errors the old pair
// is restored; an interrupted process leaves a durable backup and build-time
// verification refuses a mismatched pair. Never claim two renames are atomic.
func atomicWrite(dir, name string, data []byte) error {
	f, err := os.CreateTemp(dir, ".cn-rules-")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err = f.Chmod(0o644); err != nil {
		f.Close()
		return err
	}
	if err = f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(f.Name(), filepath.Join(dir, name))
}
func replaceBundle(assets string, old, next bundle) error {
	if err := atomicWrite(assets, R.MRSFile, next.mrs); err != nil {
		return err
	}
	if err := atomicWrite(assets, R.ManifestFile, next.manifest); err != nil {
		return errors.Join(err, atomicWrite(assets, R.MRSFile, old.mrs))
	}
	return nil
}

type rollbackGuard struct {
	Before        string `json:"before"`
	After         string `json:"after"`
	AfterMRS      string `json:"after_mrs"`
	AfterManifest string `json:"after_manifest"`
}

func apply(assets, dir, reviewed string) error {
	next, before, err := verifyCandidate(dir)
	if err != nil {
		return err
	}
	if reviewed == "" || reviewed != R.Hash(next.mrs) {
		return fmt.Errorf("--sha256 must match the reviewed candidate MRS")
	}
	current, err := readBundle(assets)
	if err != nil {
		return err
	}
	if current.fingerprint() != before.fingerprint() {
		return fmt.Errorf("repository baseline changed; prepare a fresh diff")
	}
	backup := filepath.Join(dir, "previous")
	guard := rollbackGuard{before.fingerprint(), next.fingerprint(), R.Hash(next.mrs), R.Hash(next.manifest)}
	if err = saveRollback(backup, before, guard); err != nil {
		return err
	}
	if err = replaceBundle(assets, before, next); err != nil {
		return err
	}
	fmt.Printf("Applied %s; rollback bundle: %s\n", reviewed, backup)
	return nil
}

// A candidate can be applied again: after a failed replaceBundle, or after a
// rollback. previous/ then already holds this transaction's record and is
// reused as is. Anything else found there is someone's only way back and is
// never overwritten.
func saveRollback(backup string, before bundle, guard rollbackGuard) error {
	if err := os.Mkdir(backup, 0o700); err == nil {
		if err = saveBundle(backup, before); err != nil {
			return err
		}
		return write(backup, "transaction.json", jsonBytes(guard))
	} else if !errors.Is(err, os.ErrExist) {
		return err
	}
	data, err := os.ReadFile(filepath.Join(backup, "transaction.json"))
	if err != nil {
		return fmt.Errorf("%s exists without a transaction record (an apply stopped before touching the assets); check and remove it, or prepare a fresh candidate: %w", backup, err)
	}
	var found rollbackGuard
	if err = json.Unmarshal(data, &found); err != nil {
		return fmt.Errorf("%s has an unreadable transaction record; prepare a fresh candidate: %w", backup, err)
	}
	if found != guard {
		return fmt.Errorf("%s belongs to another transaction (bundle %.12s -> %.12s, this apply is %.12s -> %.12s); if that update is still installed roll it back with this directory first, otherwise prepare a fresh candidate",
			backup, found.Before, found.After, guard.Before, guard.After)
	}
	if saved, err := readBundle(backup); err != nil || saved.fingerprint() != guard.Before {
		return fmt.Errorf("%s no longer holds the bundle its transaction record describes; prepare a fresh candidate", backup)
	}
	return nil
}
func rollback(assets, dir, reviewed string) error {
	before, err := readBundle(dir)
	if err != nil {
		return err
	}
	if _, _, err = before.verify(); err != nil {
		return err
	}
	if reviewed == "" || reviewed != R.Hash(before.mrs) {
		return fmt.Errorf("--sha256 must match the previous MRS")
	}
	data, err := os.ReadFile(filepath.Join(dir, "transaction.json"))
	if err != nil {
		return err
	}
	var guard rollbackGuard
	if err = json.Unmarshal(data, &guard); err != nil {
		return err
	}
	if guard.Before != before.fingerprint() {
		return fmt.Errorf("backup changed")
	}
	current, err := readBundle(assets)
	if err != nil {
		return err
	}
	// Also recover either known partial pair after an interrupted apply.
	knownMRS := R.Hash(current.mrs) == guard.AfterMRS || bytes.Equal(current.mrs, before.mrs)
	knownManifest := R.Hash(current.manifest) == guard.AfterManifest || bytes.Equal(current.manifest, before.manifest)
	if !knownMRS || !knownManifest {
		return fmt.Errorf("repository has unrelated changes; refusing rollback")
	}
	if err = replaceBundle(assets, current, before); err != nil {
		return err
	}
	fmt.Printf("Restored %s\n", reviewed)
	return nil
}
func run(args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("usage: cn-rules prepare|verify|apply|rollback [--assets ptcore/assets] [--dir candidate] [--ref meta] [--sha256 reviewed-hash]")
	}
	flags := flag.NewFlagSet(args[0], flag.ContinueOnError)
	assets := flags.String("assets", "ptcore/assets", "bundled asset directory")
	dir := flags.String("dir", "", "candidate directory (or previous/ for rollback)")
	ref := flags.String("ref", "meta", "upstream ref resolved once to an immutable commit")
	hash := flags.String("sha256", "", "reviewed MRS hash for apply/rollback")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return fmt.Errorf("unexpected positional arguments")
	}
	if args[0] == "verify" {
		if _, err := os.Stat(filepath.Join(*assets, ".cn-rules.lock")); err == nil {
			return fmt.Errorf("rule update lock present; finish/recover the update before building")
		}
		if *dir != "" {
			b, _, err := verifyCandidate(*dir)
			if err != nil {
				return err
			}
			fmt.Println("Candidate verified:", R.Hash(b.mrs))
			return nil
		}
		b, err := readBundle(*assets)
		if err != nil {
			return err
		}
		m, entries, err := b.verify()
		if err != nil {
			return err
		}
		if m.Schema == R.Schema && m.PolicySHA256 != R.Hash(R.RecoveryPolicy) {
			return fmt.Errorf("bundle recovery policy is stale; prepare a new candidate")
		}
		fmt.Printf("Bundle verified: %d rules, %s\n", len(entries), m.MRSSHA256)
		return nil
	}
	if *dir == "" {
		return fmt.Errorf("--dir is required")
	}
	switch args[0] {
	case "prepare":
		return prepare(*assets, *dir, *ref)
	case "apply", "rollback":
		lock := filepath.Join(*assets, ".cn-rules.lock")
		if err := os.Mkdir(lock, 0o700); err != nil {
			return fmt.Errorf("cannot acquire update lock: %w", err)
		}
		defer os.Remove(lock)
		if args[0] == "apply" {
			return apply(*assets, *dir, *hash)
		}
		return rollback(*assets, *dir, *hash)
	default:
		return fmt.Errorf("unknown command %q", args[0])
	}
}
func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "cn-rules:", err)
		os.Exit(1)
	}
}
