package com.robb3n.petrel.ui.skin.tonal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.robb3n.petrel.UiTone
import com.robb3n.petrel.ui.model.StatusTone
import com.robb3n.petrel.ui.model.Band
import com.robb3n.petrel.ui.model.ConfigUi
import com.robb3n.petrel.ui.model.ConnStatus
import com.robb3n.petrel.ui.model.HomeUi
import com.robb3n.petrel.ui.model.HopRole
import com.robb3n.petrel.ui.model.NodesUi
import com.robb3n.petrel.ui.model.OsKind
import com.robb3n.petrel.ui.model.PetrelActions
import com.robb3n.petrel.ui.model.SettingsUi
import com.robb3n.petrel.ui.model.TailnetUi
import com.robb3n.petrel.ui.model.label
import com.robb3n.petrel.ui.model.sub
import com.robb3n.petrel.ui.model.FIRST_USE_STEPS
import com.robb3n.petrel.ui.model.NodeStatusKind
import com.robb3n.petrel.ui.model.caption
import com.robb3n.petrel.ui.model.view
import com.robb3n.petrel.ui.skin.CssLines
import com.robb3n.petrel.ui.skin.CssText
import com.robb3n.petrel.ui.skin.LicensesDialog
import com.robb3n.petrel.ui.skin.LogoutDialog
import com.robb3n.petrel.ui.skin.cssLines
import com.robb3n.petrel.ui.skin.tabular
import com.robb3n.petrel.ui.pressHighlight

/*
 * Tonal 的全部页面。数值照画稿（docs/spec/assets/ui-skins/src 下的 Tonal{Home,Nodes,Tailnet}.dc.html）逐项搬：
 * `.scroll{padding:0 16 20}`（节点页下留 96 给扩展 FAB）。画稿没画的页用同一批原语拼（spec §2.8）。
 */

/**
 * 页面骨架：状态栏 inset + 顶栏 + 可滚内容。一级页的内容区止于底栏上沿（壳负责），二级页没有底栏，滚动区下方补手势条 inset。
 */
@Composable
private fun ScreenFrame(
    appBar: @Composable () -> Unit,
    nav: Boolean,
    bottom: Dp = 20.dp,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val navInset = if (nav) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        appBar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = bottom + navInset),
                content = content,
            )
            overlay?.invoke(this)
        }
    }
}

@Composable
private fun BareFrame(appBar: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        appBar()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun Gap(h: Dp) = Spacer(Modifier.height(h))

// =====================================================================
// 连接
// =====================================================================

@Composable
internal fun TonalHome(ui: HomeUi, a: PetrelActions) {
    ScreenFrame(
        appBar = {
            TonalAppBar(
                "Petrel",
                trailing = {
                    IconBtn(Ms4.refresh, "刷新", a.refresh, enabled = ui.canRefresh && !ui.refreshing, spin = ui.refreshing)
                    IconBtn(Ms4.settings, "设置", a.openSettings)
                },
            )
        },
        nav = true,
    ) {
        Hero(ui, a)
        if (ui.chain.isNotEmpty()) {
            ChainGroup(ui, a)
            Gap(16.dp)
        }
        if (ui.config.present) {
            Duo(ui, a)
        } else {
            FirstUse()
        }
    }
}

/** `.hero`：饼干键 + 状态字 + 说明，`padding:8 0 22`，居中。 */
@Composable
private fun Hero(ui: HomeUi, a: PetrelActions) {
    val c = Tonal.colors
    val status = ui.status
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CookieButton(status, a.toggle)
        CssText(status.label, tui(28f, FontWeight.Medium, 33.6f).copy(textAlign = TextAlign.Center), c.on, Modifier.padding(top = 18.dp).fillMaxWidth())
        CssLines(
            status.sub("点上面的按钮，或用状态栏快捷开关"),
            tui(14f).copy(textAlign = TextAlign.Center),
            c.onV,
            Modifier.padding(top = 6.dp).fillMaxWidth(),
        )
        if (ui.error.isNotEmpty()) {
            CssLines(
                ui.error,
                tui(14f).copy(textAlign = TextAlign.Center),
                c.err,
                Modifier.padding(top = 6.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when (status) {
            ConnStatus.NeedsLogin -> FilledBtn("去登录 tailnet", a.openTailnet, Modifier.padding(top = 16.dp), icon = Ms4.login)
            ConnStatus.NoConfig -> FilledBtn(
                ui.importLabel, a.importConfig, Modifier.padding(top = 16.dp),
                icon = Ms4.download, enabled = !ui.importing,
            )
            else -> Unit
        }
    }
}

/** 「当前链路」：先 `ts`、再前置、最后出口；出口行选中色，带延迟徽章，点按进节点页。 */
@Composable
private fun ChainGroup(ui: HomeUi, a: PetrelActions) {
    GroupHead("当前链路", trailing = { TextLink("切换出口", a.openNodes) })
    ItemGroup(ui.chain.size) { pos ->
        ui.chain.forEachIndexed { i, hop ->
            val caption = hop.role.caption(ui.groupName ?: "")
            when (hop.role) {
                HopRole.Tailnet -> Item(pos(i), leading = { Avatar(Ms4.lan) }) { ItemText(hop.name, caption) }
                HopRole.Front, HopRole.Relay -> Item(pos(i), leading = { Avatar(Ms4.swapHoriz) }) { ItemText(hop.name, caption) }
                HopRole.Exit -> {
                    val v = ui.exitView
                    Item(
                        pos(i), selected = true, onClick = a.openNodes, role = Role.Button,
                        leading = { Avatar(Ms4.publicFill, primary = true) },
                        trailing = { Badge(v.text, v.band) },
                    ) { ItemText(hop.name, caption, selected = true, titleWrap = true) }
                }
            }
        }
    }
}

/** `.duo`：两张色调卡（tailnet 用 `--terC`，配置用 `--secC`），各 `min-height:140`。 */
@Composable
private fun Duo(ui: HomeUi, a: PetrelActions) {
    val c = Tonal.colors
    val card = ui.tailnetCard
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToneCard(
            Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 10.dp),
            bg = c.terC, fg = c.onTerC, icon = Ms4.hub, key = "tailnet", onClick = a.openTailnet,
        ) {
            CssText(card.value, tui(26f, FontWeight.Medium, 29.9f), c.onTerC, softWrap = false)
            Gap(2.dp)
            CssText(card.detail, tmono(12f, FontWeight.Normal, 16.8f), c.onTerC.copy(alpha = 0.78f))
        }
        ToneCard(
            Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 10.dp, bottomStart = 28.dp),
            bg = c.secC, fg = c.onSecC, icon = Ms4.description, key = "配置", onClick = a.openConfig,
        ) {
            Gap(8.dp)
            CssLines(ui.config.name, tmono(14f, FontWeight.Medium, 18.2f), c.onSecC)
            Gap(4.dp)
            CssText(ui.config.stampShort, tmono(12f, FontWeight.Normal, 16.8f), c.onSecC.copy(alpha = 0.78f))
        }
    }
}

/** `.tc`：色调卡（`min-height:140; padding:16`），图标贴顶、其余贴底（图标的 `margin-bottom:auto`），键 13/500 78%。 */
@Composable
private fun ToneCard(
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    bg: Color,
    fg: Color,
    icon: ImageVector,
    key: String,
    onClick: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .pressHighlight(onClickLabel = key, role = Role.Button, onClick = onClick)
            .heightIn(min = 140.dp)
            .padding(16.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
        Spacer(Modifier.weight(1f).heightIn(min = 18.dp))
        CssText(key, tui(13f, FontWeight.Medium), fg.copy(alpha = 0.78f))
        body()
    }
}

/** 首次使用（NoConfig）：`.ghead` + 三个 `.item`，序号放在头像里，文案沿用 v1 的 FirstUseCard。 */
@Composable
private fun FirstUse() {
    GroupHead("首次使用")
    ItemGroup(FIRST_USE_STEPS.size) { pos ->
        FIRST_USE_STEPS.forEachIndexed { i, step ->
            Item(pos(i), leading = { TextAvatar("${i + 1}") }) { ItemText(step.title, step.desc, mono = false, subWrap = true) }
        }
    }
    Foot("之后从状态栏快捷开关一键开关，App 被强制停止也能拉起。", Modifier.padding(top = 12.dp))
}

// =====================================================================
// 节点
// =====================================================================

@Composable
internal fun TonalNodes(ui: NodesUi, a: PetrelActions) {
    val c = Tonal.colors
    if (!ui.running) {
        BareFrame(appBar = { TonalAppBar("节点") }) { CenterNote("连接后才能查看和切换节点") }
        return
    }
    ScreenFrame(
        appBar = { TonalAppBar("节点") },
        nav = true,
        bottom = 96.dp,
        overlay = {
            ExtendedFab(
                ui.testLabel, Ms4.timer, a.testDelay,
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
                enabled = !ui.testing, spin = ui.testing,
            )
        },
    ) {
        ui.groups.forEachIndexed { gi, g ->
            if (gi > 0) Gap(8.dp)
            // `.ghead` 里标题后面紧跟 `select · N` 的 chip（间距 8），不靠右
            GroupHead(g.name, spread = false) { MetaChip("select · ${g.members.size}") }
            ItemGroup(g.members.size) { pos ->
                g.members.forEachIndexed { i, m ->
                    val v = m.delay.view(ui.testing)
                    Item(
                        pos(i), selected = m.selected, role = Role.RadioButton, onClick = { a.select(g.name, m.name) },
                        leading = {
                            Icon(
                                // 选中项一律用 FILL 0 的 radio_button_checked：Fill 版是两段反向绕的子路径，nonZero 填出来是粗环（lessons/skins.md）
                                if (m.selected) Ms4.radioButtonChecked else Ms4.radioButtonUnchecked, null,
                                tint = if (m.selected) c.pri else c.onV, modifier = Modifier.size(24.dp),
                            )
                        },
                        trailing = { Badge(v.text, v.band) },
                    ) { ItemText(m.name, m.via, selected = m.selected, titleWrap = m.selected) }
                }
            }
            Foot("切换后会断开经过 ${g.name} 的现有连接，立刻生效。", Modifier.padding(top = 8.dp, bottom = 16.dp))
        }
        if (ui.baseNodes.isNotEmpty()) {
            GroupHead("链路底座")
            ItemGroup(ui.baseNodes.size) { pos ->
                ui.baseNodes.forEachIndexed { i, n ->
                    Item(
                        pos(i),
                        leading = { Avatar(if (n.isTs) Ms4.lan else Ms4.swapHoriz) },
                        trailing = n.status?.let { st ->
                            {
                                Badge(
                                    st.text,
                                    when (st.kind) {
                                        NodeStatusKind.Live -> Band.Ok
                                        NodeStatusKind.Warn -> Band.Warn
                                        NodeStatusKind.Idle -> Band.Idle
                                    },
                                    tabular = false,
                                )
                            }
                        },
                    ) { ItemText(n.name, n.caption) }
                }
            }
        }
    }
}

// =====================================================================
// tailnet
// =====================================================================

@Composable
internal fun TonalTailnet(ui: TailnetUi, a: PetrelActions) {
    when (ui) {
        TailnetUi.NotActive -> BareFrame(appBar = { TonalAppBar("tailnet", end = 16.dp) }) { CenterNote("VPN 未连接", "连接后显示 tailnet 状态") }
        is TailnetUi.NeedsLogin -> TailnetLogin(ui, a)
        is TailnetUi.Running -> TailnetRunning(ui, a)
    }
}

/** `.schip`：顶栏右侧的状态标签（高 32、`padding:0 12 0 8`、圆角 8，500 13、图标 18、间距 6）。 */
@Composable
private fun StatusChip(text: String, icon: ImageVector, bg: Color, fg: Color) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        CssText(text, tui(13f, FontWeight.Medium), fg, softWrap = false)
    }
}

@Composable
private fun TailnetLogin(ui: TailnetUi.NeedsLogin, a: PetrelActions) {
    val c = Tonal.colors
    ScreenFrame(
        appBar = { TonalAppBar("tailnet", end = 16.dp, trailing = { StatusChip("待登录", Ms4.errorFill, c.warnC, c.onWarnC) }) },
        nav = true,
    ) {
        // `.selfc` 的写法：色调大卡（圆角 28、内边距 18），留意色
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.warnC).padding(18.dp)) {
            CssText("需要登录 tailnet", tui(20f, FontWeight.Medium, 25f), c.onWarnC)
            CssLines("登录之前代理用不了：所有出口都经 tailnet 出去。", tui(14f), c.onWarnC.copy(alpha = 0.8f), Modifier.padding(top = 6.dp))
            // 登录链接本身是数据，原样显示；超长时末尾省略
            Box(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.chipBg).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (ui.ready) CssText(ui.loginURL, tmono(12f, FontWeight.Normal, 16f), c.onWarnC)
                else CssText("正在获取登录链接…", tui(12f, lineHeight = 16f), c.onWarnC.copy(alpha = 0.78f))
            }
            CssLines(
                "手机上多半打不开登录页（第三方账号登录要走代理，而代理还没通）。复制到电脑浏览器里批准，批准后这里自动变成已连接。",
                tui(12.5f, lineHeight = 18.75f), c.onWarnC.copy(alpha = 0.78f), Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledBtn("复制链接", a.copyLogin, Modifier.weight(1f), icon = Ms4.contentCopy, enabled = ui.ready, fill = true)
                OutlineBtn("浏览器打开", a.openLogin, Modifier.weight(1f), icon = Ms4.openInNew, enabled = ui.ready, color = c.onWarnC, fill = true)
            }
        }
        Gap(16.dp)
        ItemGroup(1) { pos ->
            Item(
                pos(0), minHeight = 64.dp,
                leading = { Avatar(Ms4.smartphone, bg = c.secC, fg = c.onSecC) },
                trailing = { Badge("等待批准", Band.Warn, tabular = false) },
            ) { ItemText(ui.hostname, "本机", subMono = false) }
        }
    }
}

@Composable
private fun osIcon(os: OsKind): ImageVector = when (os) {
    OsKind.Phone -> Ms4.smartphone
    OsKind.Mac -> Ms4.laptopMac
    OsKind.Windows -> Ms4.desktopWindows
    OsKind.Server -> Ms4.dns
}

@Composable
private fun TailnetRunning(ui: TailnetUi.Running, a: PetrelActions) {
    val c = Tonal.colors
    var confirmLogout by remember { mutableStateOf(false) }
    val sum = ui.summary
    ScreenFrame(
        appBar = {
            TonalAppBar("tailnet", end = 16.dp, trailing = {
                if (ui.statusTone == StatusTone.Live) StatusChip(ui.statusLabel, Ms4.checkCircleFill, c.okC, c.onOkC)
                else StatusChip(ui.statusLabel, Ms4.errorFill, c.warnC, c.onWarnC)
            })
        },
        nav = true,
    ) {
        // `.selfc`：色调大卡（`--priC`、圆角 28、内边距 18、下距 16）
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.priC).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.pri), contentAlignment = Alignment.Center) {
                    Icon(Ms4.smartphoneFill, null, tint = c.onPri, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    CssText(ui.selfName ?: "—", tmono(20f, FontWeight.Medium, 25f), c.onPriC)
                    CssText(ui.selfIp ?: "—", tmono(14f, FontWeight.Normal, 19.6f).tabular(), c.onPriC.copy(alpha = 0.8f), Modifier.padding(top = 2.dp))
                }
                IconBtn(Ms4.contentCopy, "复制地址", { ui.selfIp?.let(a.copyAddress) }, tint = c.onPriC, enabled = ui.selfIp != null)
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(sum?.ratio ?: "—", "在线")
                Chip(sum?.direct?.toString() ?: "—", "直连")
                Chip(sum?.relay?.toString() ?: "—", "中继")
            }
            OutlineBtn("登出", { confirmLogout = true }, Modifier.padding(top = 16.dp), icon = Ms4.logout, color = c.onPriC)
        }
        Gap(16.dp)
        GroupHead("节点", trailing = { GroupMeta(sum?.onlineLabel ?: "—") })
        val peers = ui.peers
        when {
            peers == null -> NoteText("—")
            peers.isEmpty() -> NoteText("tailnet 里还没有其它节点")
            else -> ItemGroup(peers.size) { pos ->
                peers.forEachIndexed { i, p ->
                    Item(
                        pos(i), minHeight = 64.dp,
                        // 没有 IPv4 的 peer 没有可复制的地址：不给长按动作
                        onLongClick = p.ip?.let { ip -> { a.copyAddress(ip) } }, onLongClickLabel = "复制地址",
                        leading = {
                            if (p.offline) Avatar(osIcon(p.os)) else Avatar(osIcon(p.os), bg = c.secC, fg = c.onSecC)
                        },
                        trailing = { Badge(p.path, p.band, tabular = false) },
                    ) { ItemText(p.name, p.ip ?: "—", faded = p.offline, subMono = true, subTabular = true) }
                }
            }
        }
    }
    if (confirmLogout) {
        LogoutDialog(onConfirm = { confirmLogout = false; a.logout() }, onDismiss = { confirmLogout = false })
    }
}

/** `.chip`：高 32、`padding:0 12`、圆角 10、`--chipBg` 底，400 13，`b` 600 tnum，间距 6。 */
@Composable
private fun Chip(bold: String, text: String) {
    val c = Tonal.colors
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(c.chipBg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CssText(bold, tui(13f, FontWeight.SemiBold).tabular(), c.onPriC, softWrap = false)
        CssText(text, tui(13f), c.onPriC, softWrap = false)
    }
}

@Composable
private fun NoteText(text: String) {
    CssText(text, tui(14f), Tonal.colors.onV, Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
}

// =====================================================================
// 配置
// =====================================================================

@Composable
internal fun TonalConfig(ui: ConfigUi, a: PetrelActions) {
    val c = Tonal.colors
    ScreenFrame(appBar = { TonalAppBar("配置", onBack = a.back) }, nav = false) {
        ui.error?.let { err ->
            // 失败横幅：error container 的色调大卡
            Column(
                Modifier
                    .semantics { liveRegion = LiveRegionMode.Assertive }
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(c.errC)
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Ms4.errorFill, null, tint = c.onErrC, modifier = Modifier.size(22.dp))
                    CssText(err.title, tui(16f, FontWeight.Medium, 22.4f), c.onErrC, Modifier.weight(1f))
                }
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.chipBg).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    CssLines(err.box, tmono(12f, FontWeight.Normal, 18f), c.onErrC)
                }
                if (err.hint.isNotEmpty()) {
                    Text(err.hint, style = tui(12.5f, lineHeight = 18.75f).cssLines(), color = c.onErrC.copy(alpha = 0.78f), modifier = Modifier.padding(top = 10.dp))
                }
            }
            Gap(16.dp)
        }
        GroupHead("当前配置")
        val cfg = ui.config
        ItemGroup(1) { pos ->
            Item(
                pos(0),
                leading = { Avatar(Ms4.description, bg = c.secC, fg = c.onSecC) },
                // 只有经界面导入、校验过的配置才有这一标签；push-config.sh 推入的没有元数据
                trailing = if (ui.verified) {
                    { Badge("校验通过", Band.Ok, tabular = false) }
                } else {
                    null
                },
            ) { ItemText(cfg.name, ui.timeLine) }
        }
        FilledBtn(
            ui.importLabel, a.importConfig,
            Modifier.padding(top = 12.dp), icon = Ms4.download, enabled = !ui.importing, fill = true,
        )
        Foot("先校验，通过才替换现有配置；VPN 运行中会自动重启生效。", Modifier.padding(top = 8.dp, bottom = 16.dp))
        ItemGroup(1) { pos ->
            Item(
                pos(0),
                leading = { Avatar(Ms4.public, bg = c.secC, fg = c.onSecC) },
                trailing = { OutlineBtn(ui.geoButtonLabel, a.replaceGeoIp, enabled = !ui.geoBusy) },
            ) { ItemText("GeoIP 数据库", "Country.mmdb · ${ui.geoText}", mono = false, subColor = if (ui.geoBad) c.err else null) }
        }
        Gap(16.dp)
        GroupHead("加载时 Petrel 会改写这些")
        ItemGroup(ui.rewrites.size) { pos ->
            ui.rewrites.forEachIndexed { i, r -> Item(pos(i)) { ItemText(r.key, r.desc, subWrap = true) } }
        }
    }
}

// =====================================================================
// 设置
// =====================================================================

@Composable
internal fun TonalSettings(ui: SettingsUi, a: PetrelActions) {
    val c = Tonal.colors
    var licenses by remember { mutableStateOf(false) }
    ScreenFrame(appBar = { TonalAppBar("设置", onBack = a.back) }, nav = false) {
        GroupHead("外观")
        // 只列已经实现的皮肤；只有一套时整行不显示
        val showSkin = ui.skins.size > 1
        ItemGroup(if (showSkin) 2 else 1) { pos ->
            var i = 0
            if (showSkin) {
                Item(pos(i++)) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        CssText("皮肤", tui(14.5f, FontWeight.Medium, 18.85f), c.on)
                        TonalSeg(ui.skins, ui.skin, a.setSkin, { it.title }, Modifier.padding(top = 8.dp))
                    }
                }
            }
            Item(pos(i)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    CssText("明暗", tui(14.5f, FontWeight.Medium, 18.85f), c.on)
                    TonalSeg(UiTone.entries, ui.tone, a.setTone, { it.title }, Modifier.padding(top = 8.dp))
                }
            }
        }
        Gap(16.dp)
        GroupHead("配置")
        ItemGroup(1) { pos ->
            Item(
                pos(0), onClick = a.openConfig, role = Role.Button, onClickLabel = "配置",
                leading = { Avatar(Ms4.description, bg = c.secC, fg = c.onSecC) },
                trailing = { Icon(Ms4.chevronRight, null, tint = c.onV, modifier = Modifier.size(24.dp)) },
            ) { ItemText("配置", ui.configName, mono = false, subMono = true) }
        }
        Gap(16.dp)
        GroupHead("关于")
        ItemGroup(2) { pos ->
            Item(
                pos(0), minHeight = 56.dp,
                trailing = { CssText("v${ui.versionName}", tmono(13f, FontWeight.Normal, 18.2f), c.onV, softWrap = false) },
            ) { CssText("版本", tui(14.5f, FontWeight.Medium, 18.85f), c.on) }
            Item(
                pos(1), minHeight = 56.dp, onClick = { licenses = true }, role = Role.Button, onClickLabel = "开源许可",
                trailing = { Icon(Ms4.chevronRight, null, tint = c.onV, modifier = Modifier.size(24.dp)) },
            ) { CssText("开源许可", tui(14.5f, FontWeight.Medium, 18.85f), c.on) }
        }
    }
    if (licenses) LicensesDialog(onDismiss = { licenses = false })
}
