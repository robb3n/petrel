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
