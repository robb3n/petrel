package cnrules

import (
	"bytes"
	"encoding/json"
	"slices"
	"testing"
)

func TestFilterRejectsBroadAndMalformedPatterns(t *testing.T) {
	entries, drop, err := Filter([]byte("# comment\r\n+.cn\n*.com\n.cn\ncn\n+.baidu.com\n"), []byte("+.baidu.com\nwww.jd.com\n"))
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(entries, []string{"+.baidu.com", "www.jd.com"}) || len(drop) != 4 {
		t.Fatalf("%v / %v", entries, drop)
	}
	for _, bad := range []string{"", "# empty", "+.cn", "https://baidu.com", "DOMAIN-SUFFIX,baidu.com", "regexp:.*", "foo..cn", "*.foo.*", "foo.cn # silent truncation", "foo.cn/path"} {
		if _, _, err := Filter([]byte(bad)); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}
func TestVerifyActualMatchingAndReproducibility(t *testing.T) {
	entries := []string{"+.baidu.com", "+.bilibili.com", "+.jd.com", "+.taobao.com"}
	mrs, err := Encode(entries)
	if err != nil {
		t.Fatal(err)
	}
	meta, _ := json.Marshal(Manifest{MRSSHA256: Hash(mrs), Entries: 4})
	_, got, err := Verify(mrs, meta)
	if err != nil {
		t.Fatal(err)
	}
	second, err := Encode(got)
	if err != nil || !bytes.Equal(mrs, second) {
		t.Fatal("non-reproducible MRS", err)
	}
	if _, _, err = Verify(append([]byte("corrupt"), mrs...), meta); err == nil {
		t.Fatal("accepted damaged resource")
	}
	for _, extra := range []string{"+.cn", "+.google.com"} {
		unsafe, _ := Encode(append(slices.Clone(entries), extra))
		meta, _ := json.Marshal(Manifest{MRSSHA256: Hash(unsafe)})
		if _, _, err = Verify(unsafe, meta); err == nil {
			t.Fatal("unsafe matching accepted", extra)
		}
	}
}
