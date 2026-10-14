package cnrules

import (
	"slices"
	"testing"
)

func TestRecoverRemovedTLDWithoutWideningVendorOrOverseas(t *testing.T) {
	inputs := [][]byte{
		[]byte("+.cn\n+.baidu.com\n"), []byte("+.jd.com\n"),
		[]byte("+.qpic.cn\n+.qlogo.cn\n+.gtimg.cn\n+.tencentcos.cn\n+.ap-tokyo.tencentcos.cn\n+.new-international.example\n"),
		[]byte("+.ap-tokyo.tencentcos.cn\n+.international.example\n"),
	}
	entries, dropped, report, err := Build(2, inputs...)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(report.Added, []string{"+.gtimg.cn", "+.qlogo.cn", "+.qpic.cn"}) {
		t.Fatal(report)
	}
	if !slices.Equal(report.Excluded, []string{"+.ap-tokyo.tencentcos.cn", "+.tencentcos.cn"}) {
		t.Fatal(report)
	}
	if !slices.Equal(dropped, []string{"+.cn"}) {
		t.Fatal(dropped)
	}
	for _, bad := range []string{"+.cn", "+.new-international.example", "+.tencentcos.cn", "+.ap-tokyo.tencentcos.cn"} {
		if slices.Contains(entries, bad) {
			t.Fatalf("unexpected expansion: %s", bad)
		}
	}
	// No removed parent: the supplemental vendor list alone grants no DIRECT policy.
	inputs[0] = []byte("+.baidu.com\n")
	_, _, report, err = Build(2, inputs...)
	if err != nil || len(report.Added) != 0 {
		t.Fatalf("%+v %v", report, err)
	}
}

func TestRecoveryBoundaryAndUnsupportedInput(t *testing.T) {
	for _, tc := range []struct {
		a, b string
		want bool
	}{
		{"+.qpic.cn", "qpic.cn", true}, {"+.qpic.cn", "mmbiz.qpic.cn", true},
		{"+.qpic.cn", "qpic.cn.example", false}, {"+.qpic.cn", "notqpic.cn", false},
		{"qpic.cn", "mmbiz.qpic.cn", false}, {"+.cloud.cn", "+.ap-tokyo.cloud.cn", true},
	} {
		if overlap(tc.a, tc.b) != tc.want {
			t.Fatal(tc)
		}
	}
	if _, _, _, err := Build(2, []byte("+.cn\n+.baidu.com\n")); err == nil {
		t.Fatal("missing sources accepted")
	}
	if _, _, _, err := Build(2, []byte("+.cn\n+.baidu.com\n"), []byte("+.jd.com\n"), []byte("*.qpic.cn\n"), []byte("+.foreign.cn\n")); err == nil {
		t.Fatal("unsupported wildcard accepted")
	}
}

// An overseas TLD must not disappear through Filter and silently admit all of
// its service members; reject the candidate so a changed source can be reviewed.
func TestRecoveryRejectsCategoryTLDs(t *testing.T) {
	for _, category := range []int{2, 3} {
		inputs := [][]byte{[]byte("+.cn\n+.baidu.com\n"), []byte("+.jd.com\n"), []byte("+.qpic.cn\n"), []byte("+.foreign.cn\n")}
		inputs[category] = append(inputs[category], []byte("+.cn\n")...)
		if _, _, _, err := Build(2, inputs...); err == nil {
			t.Fatal("category TLD silently ignored")
		}
	}
}

func wideFixture() [][]byte {
	inputs := make([][]byte, len(SourceNames(Schema)))
	for i := range inputs {
		inputs[i] = []byte("+.foreign.example\n")
	}
	inputs[0] = []byte("+.cn\n+.baidu.com\n")
	inputs[1] = []byte("+.jd.com\n")
	inputs[2] = []byte("+.qpic.cn\n")
	inputs[4] = []byte("+.sinaimg.cn\n+.oneplus.cn\n+.unreviewed-app.cn\n+.myhuaweicloud.cn\n")
	return inputs
}
func TestWideRecoveryRequiresReviewAndChecksOtherSources(t *testing.T) {
	inputs := wideFixture()
	_, _, report, err := Build(Schema, inputs...)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(report.Added, "+.sinaimg.cn") || !slices.Contains(report.Added, "+.oneplus.cn") {
		t.Fatal(report)
	}
	for _, host := range []string{"+.unreviewed-app.cn", "+.myhuaweicloud.cn"} {
		if !slices.Contains(report.Deferred, host) || slices.Contains(report.Added, host) {
			t.Fatal(report)
		}
	}
	// A foreign child found in another vendor still blocks an approved parent.
	inputs[7] = []byte("+.overseas.sinaimg.cn\n")
	_, _, report, err = Build(Schema, inputs...)
	if err != nil || !slices.Contains(report.Excluded, "+.sinaimg.cn") || slices.Contains(report.Added, "+.sinaimg.cn") {
		t.Fatalf("%+v %v", report, err)
	}
	// The global foreign list participates even without vendor-local annotations.
	inputs[len(inputs)-1] = []byte("+.overseas.oneplus.cn\n")
	_, _, report, err = Build(Schema, inputs...)
	if err != nil || !slices.Contains(report.Excluded, "+.oneplus.cn") {
		t.Fatalf("%+v %v", report, err)
	}
}

func TestWideRecoveryTLDsCannotGrantButCanDeny(t *testing.T) {
	inputs := wideFixture()
	inputs[4] = append(inputs[4], []byte("+.vendor\n")...)
	_, _, report, err := Build(Schema, inputs...)
	if err != nil || !slices.Contains(report.IgnoredServiceTLD, "+.vendor") || slices.Contains(report.Added, "+.vendor") {
		t.Fatalf("%+v %v", report, err)
	}
	inputs[len(inputs)-1] = []byte("+.cn\n+.foreign.example\n")
	_, _, report, err = Build(Schema, inputs...)
	if err != nil || len(report.Added) != 0 || !slices.Contains(report.Excluded, "+.sinaimg.cn") {
		t.Fatalf("%+v %v", report, err)
	}
}
