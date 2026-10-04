package ptcore

import (
	"crypto/rand"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fixture 里不放 GEOIP 规则，免得单测去下载 geodata。
const validMinimal = `proxies: []
proxy-groups:
  - name: PROXY
    type: select
    proxies: [DIRECT]
rules:
  - MATCH,PROXY
`

func TestValidateConfigAcceptsMinimal(t *testing.T) {
	if got := ValidateConfig(t.TempDir(), []byte(validMinimal)); got != "" {
		t.Fatalf("minimal config rejected: %q", got)
	}
}

func TestValidateConfigMissingProxy(t *testing.T) {
	bad := strings.Replace(validMinimal, "[DIRECT]", "[tokyo-iij]", 1)
	got := ValidateConfig(t.TempDir(), []byte(bad))
	if !strings.Contains(got, "not found") {
		t.Fatalf("want a 'not found' error, got %q", got)
	}
}

func TestValidateConfigInvalidYAML(t *testing.T) {
	if got := ValidateConfig(t.TempDir(), []byte("proxies: [unclosed\n  - : :")); got == "" {
		t.Fatal("invalid yaml passed validation")
	}
}

// 空文件：injectConfig 会补上 tun / ts 等内容，mihomo 实际放行。固定为这个行为，变了测试会提醒。
func TestValidateConfigEmpty(t *testing.T) {
	if got := ValidateConfig(t.TempDir(), nil); got != "" {
		t.Fatalf("empty config: want pass, got %q", got)
	}
}

func TestValidateGeoIPRejectsGarbage(t *testing.T) {
	p := filepath.Join(t.TempDir(), "Country.mmdb")
	b := make([]byte, 4096)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, b, 0o600); err != nil {
		t.Fatal(err)
	}
	if ValidateGeoIP(p) {
		t.Fatal("random bytes accepted as mmdb")
	}
	if ValidateGeoIP(filepath.Join(t.TempDir(), "missing.mmdb")) {
		t.Fatal("missing file accepted as mmdb")
	}
}
