package ptcore

import (
	"path/filepath"
	"sync"

	"github.com/metacubex/mihomo/component/mmdb"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
)

// 校验时代入的占位值：只让 injectConfig 与解析走通，不会真的去打开 fd 或连这个 socks 地址。
const (
	placeholderFd    = 0
	placeholderSocks = "127.0.0.1:1"
)

// parseMu 串行 mihomo 的配置解析（SetHomeDir + ParseWithBytes）。解析期间 mihomo 会经 temporaryUpdateGeneral
// 临时改全局设置再回滚，两次解析交错会让运行中内核的全局设置错乱，也是数据竞争。
// 校验超时后 Go 侧仍在跑，这时重试导入或替换 GeoIP 触发 RESTART，Start 的解析就会撞上它。
// 持有期间不调 Host，也不拿 mu。
var parseMu sync.Mutex

// parseConfig 把注入后的配置交给 mihomo 解析；mihomoDir 是 mihomo 的 home（含 Country.mmdb）。
func parseConfig(mihomoDir string, injected []byte) (*config.Config, error) {
	parseMu.Lock()
	defer parseMu.Unlock()
	constant.SetHomeDir(mihomoDir)
	if err := ensureDNSRules(mihomoDir); err != nil {
		return nil, err
	}
	return executor.ParseWithBytes(injected)
}

// parseAndApplyConfig 是 Start 用的：解析与 ApplyConfig 整段在 parseMu 下。只锁解析的话，校验的解析（会临时改全局设置再回滚）
// 仍能插进「解析完、应用中」的缝里，回滚时把旧值写回、盖掉刚应用的新设置。
// handOff 在解析成功、调 ApplyConfig 之前回调（Start 用它记下「fd 已交给 mihomo」）。
func parseAndApplyConfig(mihomoDir string, injected []byte, handOff func()) error {
	parseMu.Lock()
	defer parseMu.Unlock()
	constant.SetHomeDir(mihomoDir)
	if err := ensureDNSRules(mihomoDir); err != nil {
		return err
	}
	cfg, err := executor.ParseWithBytes(injected)
	if err != nil {
		return err
	}
	handOff()
	hub.ApplyConfig(cfg)
	return nil
}

// ValidateConfig 用与 Start 相同的 injectConfig 变换（占位 fd 与占位 socks 地址）后交给 mihomo 解析。
// 返回 "" 表示通过，否则是错误原文。不 ApplyConfig、不改用户配置、不碰运行中的 tsnet。
// 解析前安装随 App 附带的国内规则快照；仅写专用的内容寻址资源文件。
// homeDir 与 Start 的含义相同。解析走 parseConfig 的专用锁，与 Start 的解析串行，不会交错改全局设置。
func ValidateConfig(homeDir string, yaml []byte) string {
	injected, _, _, err := injectConfig(yaml, placeholderFd, placeholderSocks, "validate", "validate")
	if err != nil {
		return err.Error()
	}
	if _, err := parseConfig(filepath.Join(homeDir, "mihomo"), injected); err != nil {
		return err.Error()
	}
	return ""
}

// ValidateGeoIP 检查 path 是不是一份能打开的 mmdb。mihomo 加载坏的 mmdb 会 log.Fatalln 直接退出进程，
// 所以替换 Country.mmdb 之前必须先过这一关。
func ValidateGeoIP(path string) bool {
	return mmdb.Verify(path)
}

// ReloadGeoIP 让下次查询重新打开 Country.mmdb（新文件已经校验过）。
func ReloadGeoIP() {
	mmdb.ReloadIP()
}
