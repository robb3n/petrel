package com.robb3n.petrel.ui.skin.night

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.robb3n.petrel.UiTone
import com.robb3n.petrel.ui.PetrelIcons
import com.robb3n.petrel.ui.model.Band
import com.robb3n.petrel.ui.model.ConfigUi
import com.robb3n.petrel.ui.model.ConnStatus
import com.robb3n.petrel.ui.model.HomeUi
import com.robb3n.petrel.ui.model.Hop
import com.robb3n.petrel.ui.model.HopRole
import com.robb3n.petrel.ui.model.NodesUi
import com.robb3n.petrel.ui.model.PetrelActions
import com.robb3n.petrel.ui.model.SettingsUi
import com.robb3n.petrel.ui.model.EndTone
import com.robb3n.petrel.ui.model.FIRST_USE_STEPS
import com.robb3n.petrel.ui.model.NodeStatusKind
import com.robb3n.petrel.ui.model.ReadoutTone
import com.robb3n.petrel.ui.model.StatusTone
import com.robb3n.petrel.ui.model.breakAfterHyphens
import com.robb3n.petrel.ui.model.caption
import com.robb3n.petrel.ui.model.tone
import com.robb3n.petrel.ui.model.TailnetUi
import com.robb3n.petrel.ui.model.label
import com.robb3n.petrel.ui.model.routeStations
import com.robb3n.petrel.ui.model.sub
import com.robb3n.petrel.ui.model.view
import com.robb3n.petrel.ui.longPressOnly
import com.robb3n.petrel.ui.pressHighlight
import com.robb3n.petrel.ui.skin.CssLines
import com.robb3n.petrel.ui.skin.CssText
import com.robb3n.petrel.ui.skin.LicensesDialog
import com.robb3n.petrel.ui.skin.LogoutDialog
import com.robb3n.petrel.ui.skin.cssLines
import com.robb3n.petrel.ui.skin.tabular

/*
 * 夜航的全部页面。数值照画稿（docs/spec/assets/ui-skins/src 下的 NightHome、NightNodes、NightTailnet 三个 dc.html）逐项搬：
 * `.scroll` 左右 12、底 20；相邻外边距按 CSS 折叠取较大者。画稿没画的页用同一套原语拼（spec §2.8）。
 */

/** 页面骨架：状态栏 inset + 顶栏 + 可滚内容（`.scroll`：左右 12、底 20；二级页没有底栏，下面再留手势条 inset）。 */
@Composable
private fun Frame(
    topBar: @Composable () -> Unit,
    dock: Boolean,
    top: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        topBar()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = top, bottom = 20.dp + if (dock) 0.dp else navInset),
            content = content,
        )
    }
}

/** 只有顶栏的页（节点页未运行、tailnet 页 VPN 未连接）。 */
@Composable
private fun BareFrame(topBar: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        topBar()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

// =====================================================================
// 连接
// =====================================================================

private fun StatusTone.led(): LedKind = when (this) {
    StatusTone.Live -> LedKind.Live
    StatusTone.Busy -> LedKind.Busy
    StatusTone.Warn -> LedKind.Warn
    StatusTone.Idle -> LedKind.Off
}

@Composable
internal fun NightHome(ui: HomeUi, a: PetrelActions) {
    val c = Night.colors
    Frame(
        topBar = {
            // `.top`：60 高、间距 8、`padding:0 6 0 20`；品牌字 Barlow 600 20/1 字距 .2em
            Row(
                Modifier.fillMaxWidth().height(60.dp).padding(start = 20.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CssText("PETREL", ndisp(20f, FontWeight.SemiBold, 20f, 0.2.em), c.ink, softWrap = false)
                Spacer(Modifier.weight(1f))
                OutlineChip("TUN · gvisor")
                IconBtn(PetrelIcons.Refresh, "刷新", a.refresh, enabled = ui.canRefresh && !ui.refreshing, spin = ui.refreshing)
                IconBtn(PetrelIcons.Settings, "设置", a.openSettings)
            }
        },
        dock = true,
        top = 2.dp,
    ) {
        StatusPanel(ui, a)
        Gap(12.dp)
        if (ui.config.present) {
            LinksPanel(ui, a)
            // 相邻外边距折叠：panel 的 margin-bottom 12 与 `.foot` 的 margin-top 4 取 12
            Foot("状态栏快捷开关一按即起，App 被强制停止后也能拉起。", top = 12.dp)
        } else {
            FirstUsePanel()
            Foot("之后从状态栏快捷开关一键开关，App 被强制停止也能拉起。", top = 12.dp)
        }
    }
}

/**
 * `.status`：状态面板。`padding:16 16 4`；`.srow` 间距 12、底距 12，左边 `.cap` + `.big`（30/600/1.1，led 间距 12，上距 12）+ `.sub`（13、上距 6），
 * 右边 `.pwr`（右外边距 4）。待登录多一个 `.wbtn`（底距 14）；有链路时接 `.rule`（外边距 4 / -16 / 14）与航线、`.hops`。
 */
@Composable
private fun StatusPanel(ui: HomeUi, a: PetrelActions) {
    val c = Night.colors
    val status = ui.status
    val off = status == ConnStatus.Off || status == ConnStatus.NoConfig
    val live = status == ConnStatus.Connected
    Panel {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Cap("状态", "STATUS")
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Led(status.tone.led())
                        CssText(status.label, nui(30f, FontWeight.SemiBold, 33f), c.ink, softWrap = false)
                    }
                    CssLines(status.sub("点电源键，或用状态栏快捷开关"), nui(13f), c.ink2, Modifier.padding(top = 6.dp))
                    if (ui.error.isNotEmpty()) {
                        CssLines(ui.error, nui(12.5f, lineHeight = 17f), c.err, Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                PowerKey(
                    on = !off,
                    label = if (off) "连接 VPN" else "断开 VPN",
                    onClick = a.toggle,
                    modifier = Modifier.padding(end = 4.dp),
                    enabled = status != ConnStatus.NoConfig,
                )
            }
            if (status == ConnStatus.NeedsLogin) {
                WarnBtn("去登录 tailnet", a.openTailnet, Modifier.padding(bottom = 14.dp), icon = PetrelIcons.Chevron)
            }
            if (status == ConnStatus.NoConfig) {
                SolidBtn(
                    ui.importLabel, a.importConfig,
                    Modifier.fillMaxWidth(), icon = PetrelIcons.Import, enabled = !ui.importing,
                )
                Gap(16.dp)
            }
        }
        if (ui.chain.isNotEmpty()) {
            HLine(Modifier.padding(top = 4.dp))
            Gap(14.dp)
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cap("当前链路", "ROUTE")
                    Spacer(Modifier.weight(1f))
                    Readout(ui, live)
                }
                Gap(18.dp)
                RouteMap(routeStations(ui.chain), if (live) RouteMode.Live else RouteMode.Quiet, Modifier.padding(horizontal = 16.dp))
            }
            // `.route` 的 margin-bottom 6 与 `.hops` 的 margin-top 8 折叠成 8
            Gap(8.dp)
            ui.chain.forEachIndexed { i, hop ->
                HLine()
                HopRow(i + 1, hop, group = ui.groupName ?: "", live = live)
            }
            Gap(4.dp)
        } else if (status != ConnStatus.NoConfig) {
            Gap(4.dp)
        }
    }
}

/**
 * `.readout`：出口延迟（Barlow 500 36、行高 .85、tnum；Live 是 `--ok`，其它 `--ink3`）+ `small`（等宽 11/500 `--ink2`、左距 4）。
 * 行盒高 32.7（数字行盒 30.6，基线在 29.7；small 的下沿比数字多出 3 的下伸），small 的基线与数字对齐（行盒顶下 18.4 起排）。
 * 不用 Compose 的基线对齐：数字行高比字体自然高度小得多，行盒会被撑高。
 */
@Composable
private fun Readout(ui: HomeUi, live: Boolean) {
    val c = Night.colors
    val r = ui.exitView.readout
    val color = if (live && r.numeric) c.ok else c.ink3
    Row(Modifier.height(32.7.dp)) {
        // 「超时」是汉字：36 的 Barlow 字号太抢眼，缩到 24（同节点行，画稿没画这个状态）
        CssText(r.value, ndisp(if (r.timeout) 24f else 36f, FontWeight.Medium, 30.6f).tabular(), color, softWrap = false)
        if (r.unit.isNotEmpty()) {
            CssText(r.unit, nmono(11f, FontWeight.Medium, 14.3f), c.ink2, Modifier.padding(start = 4.dp, top = 18.4.dp), softWrap = false)
        }
    }
}

/** `.hops li`：序号（等宽 10.5/500 `--ink3`）+ 节点名（等宽 13.5/500，出口 600，撑满、超长省略）+ 角色（12 `--ink2`，Live 的出口 `--ok`）。基线对齐，`padding:11 16`、间距 12。 */
@Composable
private fun HopRow(n: Int, hop: Hop, group: String, live: Boolean) {
    val c = Night.colors
    val exit = hop.role == HopRole.Exit
    val role = hop.role.caption(group)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(n.toString().padStart(2, '0'), style = nmono(10.5f, FontWeight.Medium, 14.7f).cssLines(), color = c.ink3, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        Text(
            // 当前出口的长名字不截断：连字符后允许折到两行
            if (exit) hop.name.breakAfterHyphens() else hop.name,
            style = nmono(13.5f, if (exit) FontWeight.SemiBold else FontWeight.Medium, 18.9f).cssLines(),
            color = c.ink, maxLines = if (exit) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline(),
        )
        Text(role, style = nui(12f, FontWeight.Normal, 16.8f).cssLines(), color = if (exit && live) c.ok else c.ink2, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun LinksPanel(ui: HomeUi, a: PetrelActions) {
    val c = Night.colors
    val end = ui.tailnetEnd.view(spaced = false)
    val endColor: Color? = when (end.tone) {
        EndTone.Ok -> c.ok
        EndTone.Warn -> c.warn
        EndTone.Plain -> null
    }
    Panel {
        LRow(
            "tailnet", sub = ui.tailnetSub,
            leading = { RowIcon(PetrelIcons.Tailnet) },
            onClick = a.openTailnet, role = Role.Button,
            trailing = { Val(end.text ?: "—", endColor); Chev() },
        )
        HLine()
        LRow(
            "配置", sub = ui.config.name,
            leading = { RowIcon(PetrelIcons.ConfigFile) },
            onClick = a.openConfig, role = Role.Button,
            trailing = { Val(ui.config.stampNight); Chev() },
        )
    }
}

/** 首次使用（NoConfig）：`.panel` + `.cap` + 三个步骤行，序号用 `.hops .n` 的等宽小号，文案沿用 v1 的 FirstUseCard。 */
@Composable
private fun FirstUsePanel() {
    Panel {
        Box(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 12.dp)) { Cap("首次使用", "FIRST RUN") }
        FIRST_USE_STEPS.forEachIndexed { i, st -> Step((i + 1).toString().padStart(2, '0'), st.title, st.desc) }
    }
}

@Composable
private fun Step(n: String, title: String, sub: String) {
    HLine()
    LRow(
        title, sub = sub, subMono = false, subWrap = true,
        leading = { CssText(n, nmono(10.5f, FontWeight.Medium, 14.7f), Night.colors.ink3, softWrap = false) },
    )
}

// =====================================================================
// 节点
// =====================================================================

@Composable
internal fun NightNodes(ui: NodesUi, a: PetrelActions) {
    if (!ui.running) {
        BareFrame(topBar = { TitleBar("节点") }) { CenterNote("连接后才能查看和切换节点") }
        return
    }
    Frame(
        topBar = {
            TitleBar("节点", trailing = {
                OutlineBtn(
                    ui.testLabel, a.testDelay,
                    icon = PetrelIcons.Timer, enabled = !ui.testing, spin = ui.testing,
                )
            })
        },
        dock = true,
    ) {
        ui.groups.forEach { g ->
            GHead(g.name, en = "SELECT · ${g.members.size}", right = "手动选择", top = 12.dp)
            Panel {
                g.members.forEachIndexed { i, m ->
                    if (i > 0) HLine()
                    val v = m.delay.view(ui.testing)
                    NodeRow(
                        name = m.name, sub = m.subtitle, selected = m.selected,
                        onClick = { a.select(g.name, m.name) },
                        end = {
                            Meter(v.bars, v.band)
                            // spec §2.6：只有 Bad 档（≥ 200 ms 与超时）的数字用 --err，未测与测速中 --ink3，其余 --ink
                            val color = when (v.readout.tone) {
                                ReadoutTone.Idle -> Night.colors.ink3
                                ReadoutTone.Bad -> Night.colors.err
                                ReadoutTone.Normal -> Night.colors.ink
                            }
                            Msv(v.readout, color)
                        },
                    )
                }
            }
            Foot("切换后会断开经过 ${g.name} 的现有连接，立刻生效。", top = 10.dp)
        }
        if (ui.baseNodes.isNotEmpty()) {
            GHead("链路底座", en = "BASE", right = "不参与切换", top = 12.dp)
            Panel {
                ui.baseNodes.forEachIndexed { i, n ->
                    if (i > 0) HLine()
                    BaseRow(
                        name = n.name,
                        sub = n.caption,
                        on = n.status?.kind == NodeStatusKind.Live,
                        trailing = n.status?.let { st ->
                            {
                                StatusChip(
                                    st.text,
                                    when (st.kind) {
                                        NodeStatusKind.Live -> ChipTone.Ok
                                        NodeStatusKind.Warn -> ChipTone.Warn
                                        NodeStatusKind.Idle -> ChipTone.Idle
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** `.nrow`：节点行（`padding:14 14 14 16`、间距 12；选中 `--p2` 底；主文等宽 13.5/500，选中 600；副文 11.5 `--ink2`）。 */
@Composable
private fun NodeRow(
    name: String,
    sub: String?,
    selected: Boolean,
    onClick: () -> Unit,
    end: @Composable RowScope.() -> Unit,
) {
    val c = Night.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.p2 else Color.Transparent)
            .semantics { this.selected = selected }
            .pressHighlight(onClickLabel = name, role = Role.RadioButton, onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Radio(selected)
        Column(Modifier.weight(1f)) {
            val nameStyle = nmono(13.5f, if (selected) FontWeight.SemiBold else FontWeight.Medium, 17.55f)
            // 当前出口（选中项）的长名字不截断：连字符后允许折到两行
            if (selected) CssLines(name.breakAfterHyphens(), nameStyle, c.ink, maxLines = 2) else CssText(name, nameStyle, c.ink)
            if (sub != null) CssText(sub, nui(11.5f, lineHeight = 16.1f), c.ink2, Modifier.padding(top = 3.dp))
        }
        end()
    }
}

/** `.radio`：18 圆、1.5 `--ink3` 描边；选中描边 `--ok`、中心 8 的 `--ok` 点。 */
@Composable
private fun Radio(selected: Boolean) {
    val c = Night.colors
    Box(
        Modifier.size(18.dp).clip(CircleShape).border(1.5.dp, if (selected) c.ok else c.ink3, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.ok))
    }
}

/** 链路底座的行：同 `.nrow`，行首是带 5 横向外边距的 `.dotk`，副文界面字，没有选中态。 */
@Composable
private fun BaseRow(name: String, sub: String, on: Boolean, trailing: (@Composable () -> Unit)?) {
    val c = Night.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(horizontal = 5.dp)) { StatusDot(on) }
        Column(Modifier.weight(1f)) {
            CssText(name, nmono(13.5f, FontWeight.Medium, 17.55f), c.ink)
            CssText(sub, nui(11.5f, lineHeight = 16.1f), c.ink2, Modifier.padding(top = 3.dp))
        }
        trailing?.invoke()
    }
}

// =====================================================================
// tailnet
// =====================================================================

@Composable
internal fun NightTailnet(ui: TailnetUi, a: PetrelActions) {
    when (ui) {
        TailnetUi.NotActive -> BareFrame(topBar = { TitleBar("tailnet", end = 16.dp) }) { CenterNote("VPN 未连接", "连接后显示 tailnet 状态") }
        is TailnetUi.NeedsLogin -> TailnetLogin(ui, a)
        is TailnetUi.Running -> TailnetRunning(ui, a)
    }
}

@Composable
private fun TailnetLogin(ui: TailnetUi.NeedsLogin, a: PetrelActions) {
    val c = Night.colors
    Frame(
        topBar = { TitleBar("tailnet", end = 16.dp, trailing = { StatusChip("待登录", ChipTone.Warn) }) },
        dock = true,
        top = 2.dp,
    ) {
        Panel {
            Column(Modifier.padding(16.dp)) {
                Cap("登录", "LOGIN")
                CssText("需要登录 tailnet", nui(18f, FontWeight.SemiBold, 22f), c.warn, Modifier.padding(top = 12.dp))
                CssLines("登录之前代理用不了：所有出口都经 tailnet 出去。", nui(13f), c.ink2, Modifier.padding(top = 6.dp))
                // 登录链接本身是数据，原样显示；超长时末尾省略
                Box(
                    Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.p2)
                        .border(1.dp, c.line, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (ui.ready) CssText(ui.loginURL, nmono(12f, FontWeight.Normal, 16f), c.ink)
                    else CssText("正在获取登录链接…", nui(12f, lineHeight = 16f), c.ink3)
                }
                Text(
                    "手机上多半打不开登录页（第三方账号登录要走代理，而代理还没通）。复制到电脑浏览器里批准，批准后这里自动变成已连接。",
                    style = nui(11.5f, lineHeight = 17.25f).cssLines(), color = c.ink3, modifier = Modifier.padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SolidBtn("复制链接", a.copyLogin, Modifier.weight(1f), icon = PetrelIcons.Copy, enabled = ui.ready)
                    GhostBtn("浏览器打开", a.openLogin, Modifier.weight(1f), icon = PetrelIcons.OpenBrowser, enabled = ui.ready)
                }
            }
        }
        Gap(10.dp)
        Panel {
            LRow(
                ui.hostname, sub = "本机", subMono = false,
                leading = { RowIcon(PetrelIcons.Tailnet) },
                trailing = { StatusChip("等待批准", ChipTone.Warn) },
            )
        }
    }
}

@Composable
private fun TailnetRunning(ui: TailnetUi.Running, a: PetrelActions) {
    val c = Night.colors
    var confirmLogout by remember { mutableStateOf(false) }
    val sum = ui.summary
    Frame(
        topBar = {
            TitleBar("tailnet", end = 16.dp, trailing = {
                StatusChip(ui.statusLabel, if (ui.statusTone == StatusTone.Live) ChipTone.Ok else ChipTone.Warn)
            })
        },
        dock = true,
        top = 2.dp,
    ) {
        // `.self`：padding 16；`.reads` 外边距 10 / -16 / 0 通栏；`.ghost` 上距 16
        Panel {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Cap("本机", "SELF")
                CssText(ui.selfName ?: "—", nmono(24f, FontWeight.Medium, 28.8f), c.ink, Modifier.padding(top = 12.dp))
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CssText(ui.selfIp ?: "—", nmono(14f, FontWeight.Normal, 19.6f).tabular(), c.ink2, softWrap = false)
                    IconBtn(PetrelIcons.Copy, "复制地址", { ui.selfIp?.let(a.copyAddress) }, box = 40.dp, iconSize = 17.dp, enabled = ui.selfIp != null)
                }
                Gap(10.dp)
            }
            HLine()
            Row(Modifier.fillMaxWidth()) {
                Read(sum?.let { "${it.online}" } ?: "—", sum?.let { "/${it.total}" }, "在线", null, divider = false, modifier = Modifier.weight(1f))
                Read(sum?.let { "${it.direct}" } ?: "—", null, "直连", if (sum != null) c.ok else null, divider = true, modifier = Modifier.weight(1f))
                Read(sum?.let { "${it.relay}" } ?: "—", null, "中继", if (sum != null) c.warn else null, divider = true, modifier = Modifier.weight(1f))
            }
            HLine()
            Column(Modifier.padding(16.dp)) { GhostBtn("登出", { confirmLogout = true }, icon = PetrelIcons.Logout) }
        }
        GHead("节点", en = "PEERS", right = sum?.onlineLabel ?: "—", top = 14.dp)
        Panel {
            val peers = ui.peers
            when {
                peers == null -> EmptyNote("—")
                peers.isEmpty() -> EmptyNote("tailnet 里还没有其它节点")
                else -> peers.forEachIndexed { i, p ->
                    if (i > 0) HLine()
                    // 没有 IPv4 的 peer 没有可复制的地址：不给长按动作
                    PeerRow(p.name, p.ip, p.online, p.offline, p.path, p.band, p.ip?.let { ip -> { a.copyAddress(ip) } })
                }
            }
        }
        Foot("长按节点复制地址。直连是点对点 WireGuard，中继经 DERP 转发。", top = 10.dp)
    }
    if (confirmLogout) {
        LogoutDialog(onConfirm = { confirmLogout = false; a.logout() }, onDismiss = { confirmLogout = false })
    }
}

@Composable
private fun EmptyNote(text: String) {
    CssText(text, nui(13.5f), Night.colors.ink3, Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
}

/** `.reads>div`：读数格（`padding:12 16`，第二、三格左边 1 的 `--line`）。`.v` Barlow 500 30/1 tnum，`small` Barlow 500 15 `--ink2` 左距 1；`.k` 11 `--ink3`、上距 6。 */
@Composable
private fun Read(value: String, small: String?, key: String, color: Color?, divider: Boolean, modifier: Modifier = Modifier) {
    val c = Night.colors
    Column(
        modifier
            .drawBehind { if (divider) drawRect(c.line, Offset.Zero, Size(1.dp.toPx(), size.height)) }
            .padding(start = if (divider) 17.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        // 行盒高 30：数字行盒 30、基线在顶下 27；small 行高 normal（18），基线对齐后从 12 起排
        Row(Modifier.height(30.dp)) {
            CssText(value, ndisp(30f, FontWeight.Medium, 30f).tabular(), color ?: c.ink, softWrap = false)
            if (small != null) CssText(small, ndisp(15f, FontWeight.Medium, 18f), c.ink2, Modifier.padding(start = 1.dp, top = 12.dp), softWrap = false)
        }
        CssText(key, nui(11f, lineHeight = 15.4f), c.ink3, Modifier.padding(top = 6.dp))
    }
}

/** `.prow`：peer 行（`padding:10 14 10 16`、间距 12；状态点 + 名字（等宽 14/500）+ IP（等宽 11.5 `--ink3`）+ 路径徽章；离线名字 `--ink2`）。长按复制地址。 */
@Composable
private fun PeerRow(name: String, ip: String?, online: Boolean, offline: Boolean, path: String, band: Band, onLongClick: (() -> Unit)?) {
    val c = Night.colors
    Row(
        Modifier
            .fillMaxWidth()
            // 行不可点、只有长按：不带 click 语义（见 longPressOnly）
            .then(if (onLongClick != null) Modifier.longPressOnly("复制地址", onLongClick) else Modifier)
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(online)
        Column(Modifier.weight(1f)) {
            CssText(name, nmono(14f, FontWeight.Medium, 18.2f), if (offline) c.ink2 else c.ink)
            CssText(ip ?: "—", nmono(11.5f, FontWeight.Normal, 14.95f).tabular(), c.ink3, Modifier.padding(top = 2.dp))
        }
        PathBadge(path, band)
    }
}

// =====================================================================
// 配置
// =====================================================================

@Composable
internal fun NightConfig(ui: ConfigUi, a: PetrelActions) {
    val c = Night.colors
    Frame(topBar = { TitleBar("配置", onBack = a.back) }, dock = false) {
        ui.error?.let { err ->
            Gap(12.dp)
            Panel(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, outline = c.err) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(PetrelIcons.Alert, null, tint = c.err, modifier = Modifier.size(18.dp))
                        CssText(err.title, nui(14f, FontWeight.SemiBold, 18.2f), c.err)
                    }
                    Box(
                        Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.p2)
                            .border(1.dp, c.line, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        CssLines(err.box, nmono(12f, FontWeight.Normal, 18f), c.ink)
                    }
                    if (err.hint.isNotEmpty()) {
                        Text(err.hint, style = nui(11.5f, lineHeight = 17.25f).cssLines(), color = c.ink3, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
            Gap(10.dp)
        }
        if (ui.error == null) Gap(12.dp)
        Panel {
            val cfg = ui.config
            LRow(
                cfg.name, sub = ui.timeLine, subMono = false,
                leading = { RowIcon(PetrelIcons.ConfigFile) },
                // 只有经界面导入、校验过的配置才有这一标签；push-config.sh 推入的没有元数据
                trailing = if (ui.verified) {
                    { StatusChip("校验通过", ChipTone.Ok) }
                } else {
                    null
                },
            )
        }
        Gap(10.dp)
        SolidBtn(ui.importLabel, a.importConfig, Modifier.fillMaxWidth(), icon = PetrelIcons.Import, enabled = !ui.importing)
        Foot("先校验，通过才替换现有配置；VPN 运行中会自动重启生效。", top = 8.dp)
        Gap(12.dp)
        Panel {
            LRow(
                "GeoIP 数据库", sub = "Country.mmdb · ${ui.geoText}", subColor = if (ui.geoBad) c.err else null,
                trailing = { OutlineBtn(ui.geoButtonLabel, a.replaceGeoIp, enabled = !ui.geoBusy) },
            )
        }
        GHead("加载时 Petrel 会改写这些", en = "REWRITES", top = 14.dp)
        Panel {
            ui.rewrites.forEachIndexed { i, r -> Rewrite(r.key, r.desc, first = i == 0) }
        }
    }
}

@Composable
private fun Rewrite(key: String, desc: String, first: Boolean = false) {
    if (!first) HLine()
    LRow(key, sub = desc, subMono = false, subWrap = true)
}

// =====================================================================
// 设置
// =====================================================================

@Composable
internal fun NightSettings(ui: SettingsUi, a: PetrelActions) {
    val c = Night.colors
    var licenses by remember { mutableStateOf(false) }
    Frame(topBar = { TitleBar("设置", onBack = a.back) }, dock = false) {
        GHead("外观", en = "APPEARANCE", top = 12.dp)
        Panel {
            Column(Modifier.padding(16.dp)) {
                // 只列已经实现的皮肤；只有一套时整行不显示
                if (ui.skins.size > 1) {
                    CssText("皮肤", nui(13f, FontWeight.Medium, 18.2f), c.ink2)
                    NightSeg(ui.skins, ui.skin, a.setSkin, label = { it.title }, modifier = Modifier.padding(top = 8.dp))
                    Gap(16.dp)
                }
                CssText("明暗", nui(13f, FontWeight.Medium, 18.2f), c.ink2)
                NightSeg(UiTone.entries, ui.tone, a.setTone, label = { it.title }, modifier = Modifier.padding(top = 8.dp))
            }
        }
        GHead("配置", en = "CONFIG", top = 14.dp)
        Panel {
            LRow(
                "配置", sub = ui.configName,
                leading = { RowIcon(PetrelIcons.ConfigFile) },
                onClick = a.openConfig, role = Role.Button,
                trailing = { Chev() },
            )
        }
        GHead("关于", en = "ABOUT", top = 14.dp)
        Panel {
            LRow("版本", trailing = { Val("v${ui.versionName}") })
            HLine()
            LRow("开源许可", onClick = { licenses = true }, role = Role.Button, trailing = { Chev() })
        }
    }
    if (licenses) LicensesDialog(onDismiss = { licenses = false })
}
