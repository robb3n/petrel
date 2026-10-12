package main

import (
	"bytes"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	R "github.com/robb3n/petrel/core/internal/cnrules"
)

const testRevision = "0123456789012345678901234567890123456789"

var testInput = []byte("+.baidu.com\n+.bilibili.com\n+.taobao.com\n+.jd.com\n+.cn\n")

func fixture(t *testing.T) (string, string, bundle) {
	t.Helper()
	dir := t.TempDir()
	assets := filepath.Join(dir, "assets")
	if err := os.Mkdir(assets, 0o700); err != nil {
		t.Fatal(err)
	}
	entries, _, err := R.Filter(testInput)
	if err != nil {
		t.Fatal(err)
	}
	mrs, err := R.Encode(entries)
	if err != nil {
		t.Fatal(err)
	}
	before := bundle{mrs, jsonBytes(R.Manifest{MRSSHA256: R.Hash(mrs)})}
	if err = saveBundle(assets, before); err != nil {
		t.Fatal(err)
	}
	return assets, filepath.Join(dir, "candidate"), before
}
func downloadFixture(t *testing.T) func(string) ([]byte, error) {
	t.Helper()
	return func(u string) ([]byte, error) {
		switch u {
		case repo + "meta":
			return []byte(`{"sha":"` + testRevision + `","commit":{"committer":{"date":"2026-10-09T00:00:00Z"}}}`), nil
		case rawRoot + testRevision + "/geo/geosite/cn.list":
			return testInput, nil
		case rawRoot + testRevision + "/geo/geosite/geolocation-cn.list":
			return []byte("+.new.example.cn\n"), nil
		default:
			t.Fatalf("request not pinned to a single revision: %s", u)
			return nil, nil
		}
	}
}
func TestPrepareApplyRollbackAndInterruptedPair(t *testing.T) {
	assets, dir, before := fixture(t)
	if err := prepareWithFetch(assets, dir, "meta", downloadFixture(t)); err != nil {
		t.Fatal(err)
	}
	current, _ := readBundle(assets)
	if current.fingerprint() != before.fingerprint() {
		t.Fatal("prepare mutated assets")
	}
	next, _, err := verifyCandidate(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err = run([]string{"apply", "--assets", assets, "--dir", dir, "--sha256", "wrong"}); err == nil {
		t.Fatal("unreviewed hash accepted")
	}
	if err = run([]string{"apply", "--assets", assets, "--dir", dir, "--sha256", R.Hash(next.mrs)}); err != nil {
		t.Fatal(err)
	}
	installed, _ := readBundle(assets)
	if installed.fingerprint() != next.fingerprint() {
		t.Fatal("wrong applied bundle")
	}
	// Interrupted two-file replacement: new MRS with old manifest is rejected by
	// verify but recoverable using the recorded transaction's known file hashes.
	if err = atomicWrite(assets, R.ManifestFile, before.manifest); err != nil {
		t.Fatal(err)
	}
	if err = run([]string{"verify", "--assets", assets}); err == nil {
		t.Fatal("partial update accepted")
	}
	if err = run([]string{"rollback", "--assets", assets, "--dir", filepath.Join(dir, "previous"), "--sha256", R.Hash(before.mrs)}); err != nil {
		t.Fatal(err)
	}
	restored, _ := readBundle(assets)
	if restored.fingerprint() != before.fingerprint() {
		t.Fatal("rollback not byte-exact")
	}
}
func TestCandidateTamperAndBaselineConflict(t *testing.T) {
	for _, target := range []string{"cn.list", "added.txt", R.MRSFile, "baseline.sources.json"} {
		t.Run(target, func(t *testing.T) {
			assets, dir, _ := fixture(t)
			if err := prepareWithFetch(assets, dir, "meta", downloadFixture(t)); err != nil {
				t.Fatal(err)
			}
			if err := write(dir, target, []byte("changed")); err != nil {
				t.Fatal(err)
			}
			if _, _, err := verifyCandidate(dir); err == nil {
				t.Fatal("tampered candidate accepted")
			}
		})
	}
	assets, dir, before := fixture(t)
	if err := prepareWithFetch(assets, dir, "meta", downloadFixture(t)); err != nil {
		t.Fatal(err)
	}
	next, _, _ := verifyCandidate(dir)
	// Keep MRS unchanged but alter manifest to represent concurrent repository work.
	changed := append(bytes.Clone(before.manifest), ' ')
	if err := write(assets, R.ManifestFile, changed); err != nil {
		t.Fatal(err)
	}
	if err := apply(assets, dir, R.Hash(next.mrs)); err == nil {
		t.Fatal("overwrote concurrent change")
	}
	got, _ := os.ReadFile(filepath.Join(assets, R.ManifestFile))
	if !bytes.Equal(got, changed) {
		t.Fatal("conflict lost data")
	}
}
func TestFailedFetchAndLockPreserveAssets(t *testing.T) {
	assets, dir, before := fixture(t)
	good := downloadFixture(t)
	if err := prepareWithFetch(assets, dir, "meta", func(u string) ([]byte, error) {
		if strings.HasSuffix(u, "geolocation-cn.list") {
			return nil, errors.New("offline")
		}
		return good(u)
	}); err == nil {
		t.Fatal("download failure ignored")
	}
	if _, err := os.Stat(dir); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("partial candidate published")
	}
	got, _ := readBundle(assets)
	if got.fingerprint() != before.fingerprint() {
		t.Fatal("failed fetch changed baseline")
	}
	lock := filepath.Join(assets, ".cn-rules.lock")
	if err := os.Mkdir(lock, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := run([]string{"verify", "--assets", assets}); err == nil {
		t.Fatal("build ignored update lock")
	}
	if err := run([]string{"apply", "--assets", assets, "--dir", dir}); err == nil {
		t.Fatal("concurrent update allowed")
	}
	if _, err := os.Stat(lock); err != nil {
		t.Fatal("removed another updater's lock")
	}
}
func TestRollbackRefusesUnrelatedChanges(t *testing.T) {
	assets, dir, before := fixture(t)
	if err := prepareWithFetch(assets, dir, "meta", downloadFixture(t)); err != nil {
		t.Fatal(err)
	}
	next, _, _ := verifyCandidate(dir)
	if err := apply(assets, dir, R.Hash(next.mrs)); err != nil {
		t.Fatal(err)
	}
	if err := write(assets, R.ManifestFile, []byte("unrelated work")); err != nil {
		t.Fatal(err)
	}
	if err := rollback(assets, filepath.Join(dir, "previous"), R.Hash(before.mrs)); err == nil {
		t.Fatal("rollback overwrote unrelated work")
	}
}
func TestDownloadStatusAndSize(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/error":
			w.WriteHeader(503)
		case "/huge":
			_, _ = w.Write(make([]byte, (16<<20)+1))
		default:
			_, _ = w.Write([]byte("ok"))
		}
	}))
	defer server.Close()
	for _, path := range []string{"/error", "/huge"} {
		if _, err := fetch(server.Client(), server.URL+path); err == nil {
			t.Fatal("invalid download accepted", path)
		}
	}
	if b, err := fetch(server.Client(), server.URL+"/ok"); err != nil || string(b) != "ok" {
		t.Fatal("valid download failed", err)
	}
}

func TestSecondRenameFailureRestoresOldMRS(t *testing.T) {
	assets, _, before := fixture(t)
	if err := os.Remove(filepath.Join(assets, R.ManifestFile)); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(assets, R.ManifestFile), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := replaceBundle(assets, before, bundle{[]byte("replacement"), []byte("metadata")}); err == nil {
		t.Fatal("second rename unexpectedly succeeded")
	}
	got, err := os.ReadFile(filepath.Join(assets, R.MRSFile))
	if err != nil || !bytes.Equal(got, before.mrs) {
		t.Fatal("first file not restored", err)
	}
}

// The same candidate is applied again after a rollback, and after an apply that
// stopped between writing the rollback record and replacing the assets.
func TestApplyAgainReusesItsRollbackRecord(t *testing.T) {
	assets, dir, before := fixture(t)
	if err := prepareWithFetch(assets, dir, "meta", downloadFixture(t)); err != nil {
		t.Fatal(err)
	}
	next, _, _ := verifyCandidate(dir)
	hash, backup := R.Hash(next.mrs), filepath.Join(dir, "previous")
	if err := apply(assets, dir, hash); err != nil {
		t.Fatal(err)
	}
	record, err := os.ReadFile(filepath.Join(backup, "transaction.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err = rollback(assets, backup, R.Hash(before.mrs)); err != nil {
		t.Fatal(err)
	}
	if err = apply(assets, dir, hash); err != nil {
		t.Fatalf("second apply after rollback: %v", err)
	}
	if got, _ := readBundle(assets); got.fingerprint() != next.fingerprint() {
		t.Fatal("second apply did not install the candidate")
	}
	if got, _ := os.ReadFile(filepath.Join(backup, "transaction.json")); !bytes.Equal(got, record) {
		t.Fatal("rollback record rewritten")
	}
	if saved, _ := readBundle(backup); saved.fingerprint() != before.fingerprint() {
		t.Fatal("rollback bundle changed")
	}
	if err = rollback(assets, backup, R.Hash(before.mrs)); err != nil {
		t.Fatalf("rollback after the second apply: %v", err)
	}

	// Record written, assets untouched: the retry completes the same transaction.
	assets, dir, before = fixture(t)
	if err = prepareWithFetch(assets, dir, "meta", downloadFixture(t)); err != nil {
		t.Fatal(err)
	}
	next, _, _ = verifyCandidate(dir)
	guard := rollbackGuard{before.fingerprint(), next.fingerprint(), R.Hash(next.mrs), R.Hash(next.manifest)}
	if err = saveRollback(filepath.Join(dir, "previous"), before, guard); err != nil {
		t.Fatal(err)
	}
	if err = apply(assets, dir, R.Hash(next.mrs)); err != nil {
		t.Fatalf("retry after an interrupted apply: %v", err)
	}
}

// previous/ left by anything else is someone's only way back: refuse, name the
// transaction, and touch neither the record nor the assets.
func TestApplyRefusesForeignRollbackRecord(t *testing.T) {
	for name, spoil := range map[string]func(t *testing.T, backup string, guard rollbackGuard){
		"other transaction": func(t *testing.T, backup string, guard rollbackGuard) {
			guard.After = strings.Repeat("ab", 32)
			if err := write(backup, "transaction.json", jsonBytes(guard)); err != nil {
				t.Fatal(err)
			}
		},
		"no record": func(t *testing.T, backup string, _ rollbackGuard) {
			if err := os.Remove(filepath.Join(backup, "transaction.json")); err != nil {
				t.Fatal(err)
			}
		},
		"unreadable record": func(t *testing.T, backup string, _ rollbackGuard) {
			if err := write(backup, "transaction.json", []byte("{")); err != nil {
				t.Fatal(err)
			}
		},
		"bundle changed": func(t *testing.T, backup string, _ rollbackGuard) {
			if err := write(backup, R.MRSFile, []byte("tampered")); err != nil {
				t.Fatal(err)
			}
		},
	} {
		t.Run(name, func(t *testing.T) {
			assets, dir, before := fixture(t)
			if err := prepareWithFetch(assets, dir, "meta", downloadFixture(t)); err != nil {
				t.Fatal(err)
			}
			next, _, _ := verifyCandidate(dir)
			backup := filepath.Join(dir, "previous")
			guard := rollbackGuard{before.fingerprint(), next.fingerprint(), R.Hash(next.mrs), R.Hash(next.manifest)}
			if err := saveRollback(backup, before, guard); err != nil {
				t.Fatal(err)
			}
			spoil(t, backup, guard)
			snapshot := func() string {
				var all []byte
				for _, n := range []string{R.MRSFile, R.ManifestFile, "transaction.json"} {
					b, _ := os.ReadFile(filepath.Join(backup, n))
					all = append(append(all, b...), 0)
				}
				return R.Hash(all)
			}
			kept := snapshot()
			err := apply(assets, dir, R.Hash(next.mrs))
			if err == nil {
				t.Fatal("overwrote an existing rollback record")
			}
			if name == "other transaction" && !strings.Contains(err.Error(), "abababababab") {
				t.Errorf("error does not name the transaction found: %v", err)
			}
			if snapshot() != kept {
				t.Error("rollback record modified")
			}
			if got, _ := readBundle(assets); got.fingerprint() != before.fingerprint() {
				t.Error("assets replaced despite the refusal")
			}
		})
	}
}
