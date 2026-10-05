package com.robb3n.petrel.ui.skin.shoal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import com.robb3n.petrel.ui.model.ConfigUi
import com.robb3n.petrel.ui.model.ConnStatus
import com.robb3n.petrel.ui.model.DelayView
import com.robb3n.petrel.ui.model.HomeUi
import com.robb3n.petrel.ui.model.Hop
import com.robb3n.petrel.ui.model.HopRole
import com.robb3n.petrel.ui.model.NodesUi
import com.robb3n.petrel.ui.model.OsKind
import com.robb3n.petrel.ui.model.PetrelActions
import com.robb3n.petrel.ui.model.SettingsUi
import com.robb3n.petrel.ui.model.FIRST_USE_STEPS
import com.robb3n.petrel.ui.model.NodeStatusKind
import com.robb3n.petrel.ui.model.StatusTone
import com.robb3n.petrel.ui.model.breakAfterHyphens
import com.robb3n.petrel.ui.model.caption
import com.robb3n.petrel.ui.model.tone
import com.robb3n.petrel.ui.model.TailnetUi
import com.robb3n.petrel.ui.model.label
import com.robb3n.petrel.ui.model.sub
import com.robb3n.petrel.ui.model.view
import com.robb3n.petrel.ui.pressHighlight
import com.robb3n.petrel.UiTone
import com.robb3n.petrel.ui.skin.LicensesDialog
import com.robb3n.petrel.ui.skin.LogoutDialog
import com.robb3n.petrel.ui.skin.CssText
import com.robb3n.petrel.ui.skin.CssLines
import com.robb3n.petrel.ui.skin.cssLineBox
import com.robb3n.petrel.ui.skin.tabular

/*
 * Shoal 的全部页面。数值照画稿（docs/spec/assets/ui-skins/src 下的 Main、ShoalNodes、ShoalTailnet 三个 dc.html）逐项搬：
 * `.scroll{padding:4 12 100}`、相邻外边距按 CSS 折叠取较大者（见 Mu3ic shoal-mockup-porting.md）。
 */

private val ScrollShape24 = RoundedCornerShape(24.dp)

/** 页面骨架：状态栏 inset + 顶栏 + 可滚内容（`.scroll`：左右 12、上 4、下 100 + 手势条 inset；二级页没有悬浮底栏，下留 16）。 */
@Composable
private fun ScreenFrame(
    appBar: @Composable () -> Unit,
    floatingNav: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        appBar()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = (if (floatingNav) 100.dp else 16.dp) + navInset),
            content = content,
        )
    }
}

/** 居中的两行提示（节点页未运行、tailnet 页 VPN 未连接）。 */
@Composable
private fun CenterNote(title: String, sub: String? = null) {
    val c = Shoal.colors
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().padding(bottom = 100.dp + navInset), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CssText(title, if (sub == null) ui(14f) else ui(16f, FontWeight.Medium), if (sub == null) c.tx2 else c.tx)
            if (sub != null) CssText(sub, ui(13f), c.tx2)
        }
    }
}

// =====================================================================
// 连接
// =====================================================================

private fun StatusTone.tagKind(): TagKind = when (this) {
    StatusTone.Idle, StatusTone.Busy -> TagKind.Plain
    StatusTone.Warn -> TagKind.Warn
    StatusTone.Live -> TagKind.Live
}

@Composable
internal fun ShoalHome(ui: HomeUi, a: PetrelActions) {
    ScreenFrame(
        appBar = {
            ShoalAppBar(
                "Petrel",
                titleTrailing = { Tag(ui.status.label, kind = ui.status.tone.tagKind()) },
                actions = {
                    ShoalIconButton(Ms.refresh, "刷新", a.refresh, enabled = ui.canRefresh && !ui.refreshing, spin = ui.refreshing)
                    ShoalIconButton(Ms.settings, "设置", a.openSettings)
                },
            )
        },
        floatingNav = true,
    ) {
        Hero(ui, a)
        if (ui.chain.isNotEmpty()) {
            Gap(12.dp)
            ChainIsle(ui, a)
        }
        Gap(12.dp)
        if (ui.config.present) {
            LinksIsle(ui, a)
            // 相邻外边距折叠：isle 的 margin-bottom 12 与 `.foot` 的 margin-top 10 取 12
            Foot("状态栏快捷开关一按即起，App 被强制停止后也能拉起。", side = 6.dp, top = 12.dp)
        } else {
            FirstUseIsle()
            Foot("之后从状态栏快捷开关一键开关，App 被强制停止也能拉起。", side = 6.dp, top = 12.dp)
        }
    }
}

/** `.hero` 的 135° 渐变（0% / 55% / 100%）：CSS 的渐变线与盒子宽高比无关，固定 45° 方向，按 CSS 规则算起终点。 */
private fun Modifier.heroSurface(c: ShoalColors, shape: Shape): Modifier =
    this
        .shadow(8.dp, shape, clip = false, ambientColor = c.shadow, spotColor = c.shadow)
        .clip(shape)
        .drawBehind {
            val w = size.width
            val h = size.height
            val brush = Brush.linearGradient(
                colorStops = arrayOf(0f to c.heroA, 0.55f to c.heroB, 1f to c.heroC),
                start = Offset((w - h) / 4f, (h - w) / 4f),
                end = Offset((3f * w + h) / 4f, (3f * h + w) / 4f),
            )
            drawRect(brush)
        }
        .border(0.5.dp, c.isleRing, shape)

@Composable
private fun Hero(ui: HomeUi, a: PetrelActions) {
    val c = Shoal.colors
    val status = ui.status
    val off = status == ConnStatus.Off || status == ConnStatus.NoConfig
    val surface = if (off) Modifier.isleSurface(c, ScrollShape24) else Modifier.heroSurface(c, ScrollShape24)
    val fg = if (off) c.tx else c.heroInk
    val subColor = if (off) c.tx2 else c.heroInk.copy(alpha = 0.85f)
    Column(Modifier.fillMaxWidth().then(surface).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                CssText(status.label, ui(22f, FontWeight.SemiBold, 27.5f), fg)
                CssLines(status.sub("点右边的开关，或用状态栏快捷开关"), ui(12f), subColor, Modifier.padding(top = 4.dp))
                if (ui.error.isNotEmpty()) {
                    val errColor = if (off) c.redFg else c.heroInk
                    Row(
                        Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Ms.error, null, tint = errColor, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                        CssLines(ui.error, ui(12f), errColor, Modifier.weight(1f))
                    }
                }
            }
            Sw(
                checked = !off,
                onClick = a.toggle,
                label = if (off) "连接 VPN" else "断开 VPN",
                modifier = Modifier.padding(top = 2.dp),
                enabled = status != ConnStatus.NoConfig,
                busy = status == ConnStatus.Connecting,
                style = if (off) SwStyle.Plain else SwStyle.Hero,
            )
        }
        if (status != ConnStatus.NoConfig) {
            val bg = if (off) c.sf2 else c.sf
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val d = ui.exitView.readout
                // 点格子进对应的 tab（同 Mu3ic 首页统计格）：pager 滑过去，底栏胶囊跟着滑
                Stat(
                    value = d.value, unit = d.unit,
                    key = "出口延迟", color = c.s0, bg = bg, onClick = a.openNodes, modifier = Modifier.weight(1f),
                )
                Stat(
                    value = ui.tailnet?.ratio ?: "—",
                    unit = "", key = "tailnet 在线", color = c.s3, bg = bg, onClick = a.openTailnet, modifier = Modifier.weight(1f),
                )
                Stat(
                    value = ui.groupSize?.toString() ?: "—",
                    unit = "", key = "可选出口", color = c.s2, bg = bg, onClick = a.openNodes, modifier = Modifier.weight(1f),
                )
            }
        }
        if (status == ConnStatus.NeedsLogin) {
            OlBtn("去登录 tailnet", a.openTailnet, Modifier.padding(top = 12.dp), icon = Ms.login, onHero = true)
        }
        if (status == ConnStatus.NoConfig) {
            OlBtn(ui.importLabel, a.importConfig, Modifier.padding(top = 12.dp), icon = Ms.download, enabled = !ui.importing)
        }
    }
}

/** `.stat`：圆角 14、`padding:8 10 7`；值 Oswald 500 22/1.1、单位 12 `--tx2`，标签 10.5 `--tx2`。可点（[onClick] 进对应的 tab），按下叠一层高亮。 */
@Composable
private fun Stat(value: String, unit: String, key: String, color: Color, bg: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Shoal.colors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .pressHighlight(onClickLabel = "打开$key", role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 7.dp),
    ) {
        // `.v` 行高 1.1 = 24.2，但里面 12px 的 small（行高 normal）的下沿把行盒撑到约 25（画稿实测 stat 高 56）
        val style = ui(22f, FontWeight.Medium, 25f).copy(fontFamily = Oswald, letterSpacing = 0.01.em).tabular()
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(value) }
            if (unit.isNotEmpty()) {
                withStyle(SpanStyle(color = c.tx2, fontFamily = Rubik, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)) {
                    append(" ")
                    append(unit)
                }
            }
        }
        val box = androidx.compose.ui.platform.LocalDensity.current.run { style.lineHeight.toDp() }
        Text(text, style = style, maxLines = 1, softWrap = false, modifier = Modifier.cssLineBox(box))
        CssText(key, ui(10.5f), c.tx2, Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ChainIsle(ui: HomeUi, a: PetrelActions) {
    val c = Shoal.colors
    Isle {
        PopHead(
            "当前链路",
            sub = ui.chainSub,
            tile = { Tile(Ms.altRoute, TileTone.Ol) },
            trailing = { MiniBtn("切换", a.openNodes, trailingIcon = Ms.chevronRight) },
        )
        Column(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.sf2).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ui.chain.forEachIndexed { i, hop ->
                // 每跳往下画到下一跳里的连接线要压在下一跳的底色（出口的 butter）之上：前面的行 zIndex 更高、后画
                HopRow(
                    hop, last = i == ui.chain.lastIndex, group = ui.groupName ?: "", delay = ui.exitView, onClick = a.openNodes,
                    modifier = Modifier.zIndex((ui.chain.size - i).toFloat()),
                )
            }
        }
    }
}

/**
 * `.hop`：链路上的一跳（同 `.row`，图标 19 `--tx2`；非末行有 2 宽的竖线连到下一跳：`left:18.5; top:37; bottom:-14`）。
 * 末跳是出口，`.hop.sel`（`--butter` 底），带延迟徽章。
 */
@Composable
private fun HopRow(hop: Hop, last: Boolean, group: String, delay: DelayView, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Shoal.colors
    val exit = hop.role == HopRole.Exit
    val shape = RoundedCornerShape(14.dp)
    val icon = when (hop.role) {
        HopRole.Tailnet -> Ms.lan
        HopRole.Front, HopRole.Relay -> Ms.swapHoriz
        HopRole.Exit -> Ms.radioButtonChecked
    }
    val sub = hop.role.caption(group, detailed = true)
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) {
                    drawRoundRect(
                        color = c.sf3,
                        topLeft = Offset(18.5.dp.toPx(), 37.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height + 14.dp.toPx() - 37.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                    )
                }
            }
            .background(if (exit) c.butter else Color.Transparent, shape)
            .clip(shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (exit) c.butterInk else c.tx2, modifier = Modifier.size(19.dp))
        Column(Modifier.weight(1f)) {
            // 当前出口的长名字不截断：连字符后允许折到两行
            if (exit) CssLines(hop.name.breakAfterHyphens(), ui(13.5f, FontWeight.Medium), c.butterInk, maxLines = 2)
            else CssText(hop.name, ui(13.5f, FontWeight.Medium), c.tx)
            CssText(sub, ui(11.5f), c.tx2, Modifier.padding(top = 1.dp))
        }
        if (exit) {
            LatBadge(delay.text, delay.band)
        }
    }
}

@Composable
private fun LinksIsle(ui: HomeUi, a: PetrelActions) {
    val endText = ui.tailnetEnd.view(spaced = true).text
    Isle(padding = 6.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ListRow(
                "tailnet", sub = ui.tailnetSub,
                leading = { Tile(Ms.lan, TileTone.Mint) },
                onClick = a.openTailnet, role = Role.Button,
                trailing = { RowEnd(endText, Ms.chevronRight) },
            )
            ListRow(
                "配置", sub = "${ui.config.name} · ${ui.config.stampShort}",
                leading = { Tile(Ms.description, TileTone.Butter) },
                onClick = a.openConfig, role = Role.Button,
                trailing = { RowEnd(icon = Ms.chevronRight) },
            )
        }
    }
}

/** 首次使用（NoConfig）：`.isle` + `.ph` + `.card` 里三个 `.row`，文案沿用 v1 的 FirstUseCard。 */
@Composable
private fun FirstUseIsle() {
    Isle {
        PopHead("首次使用", tile = { Tile(Ms.info, TileTone.Brown) })
        ShoalCard(Modifier.padding(top = 12.dp)) {
            FIRST_USE_STEPS.forEachIndexed { i, step ->
                ListRow(step.title, sub = step.desc, subWrap = true, leading = { TextTile("${i + 1}", if (i == 0) TileTone.Ol else TileTone.Butter) })
            }
        }
    }
}

// =====================================================================
// 节点
// =====================================================================

@Composable
internal fun ShoalNodes(ui: NodesUi, a: PetrelActions) {
    if (!ui.running) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ShoalAppBar("节点", end = 14.dp)
            CenterNote("连接后才能查看和切换节点")
        }
        return
    }
    ScreenFrame(
        appBar = {
            ShoalAppBar("节点", end = 14.dp, actions = {
                MiniBtn(
                    ui.testLabel, a.testDelay,
                    size = MiniSize.OnWall, leadingIcon = Ms.speed, enabled = !ui.testing, spin = ui.testing,
                )
            })
        },
        floatingNav = true,
    ) {
        ui.groups.forEachIndexed { gi, g ->
            if (gi > 0) Gap(12.dp)
            Isle {
                PopHead(g.name, sub = g.subtitle, tile = { Tile(Ms.hub, TileTone.Sage) }, trailing = { Tag("select") })
                ShoalCard(Modifier.padding(top = 12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        g.members.forEach { m ->
                            val v = m.delay.view(ui.testing)
                            ListRow(
                                m.name, sub = m.subtitle, titleWrap = m.selected,
                                icon = if (m.selected) Ms.radioButtonChecked else Ms.radioButtonUnchecked,
                                selected = m.selected,
                                role = Role.RadioButton,
                                onClick = { a.select(g.name, m.name) },
                                trailing = { LatBadge(v.text, v.band) },
                            )
                        }
                    }
                }
                Foot("切换后会断开经过 ${g.name} 的现有连接，立刻生效。")
            }
        }
        if (ui.baseNodes.isNotEmpty()) {
            // isle 的 margin-bottom 12 与 `.lbl` 的 margin-top 16 折叠成 16
            Gap(if (ui.groups.isEmpty()) 0.dp else 16.dp)
            Lbl("链路底座", em = "不参与切换")
            Isle(padding = 6.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ui.baseNodes.forEach { n ->
                        ListRow(
                            n.name,
                            sub = n.caption,
                            leading = { Tile(if (n.isTs) Ms.lan else Ms.swapHoriz, if (n.isTs) TileTone.Mint else TileTone.Brown) },
                            trailing = n.status?.let { st ->
                                {
                                    Tag(
                                        st.text,
                                        kind = when (st.kind) {
                                            NodeStatusKind.Live -> TagKind.Live
                                            NodeStatusKind.Warn -> TagKind.Warn
                                            NodeStatusKind.Idle -> TagKind.Plain
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
}

// =====================================================================
// tailnet
// =====================================================================

@Composable
internal fun ShoalTailnet(ui: TailnetUi, a: PetrelActions) {
    when (ui) {
        TailnetUi.NotActive -> {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                ShoalAppBar("tailnet")
                CenterNote("VPN 未连接", "连接后显示 tailnet 状态")
            }
        }
        is TailnetUi.NeedsLogin -> TailnetLogin(ui, a)
        is TailnetUi.Running -> TailnetRunning(ui, a)
    }
}

@Composable
private fun TailnetLogin(ui: TailnetUi.NeedsLogin, a: PetrelActions) {
    val c = Shoal.colors
    ScreenFrame(
        appBar = { ShoalAppBar("tailnet", titleTrailing = { Tag("待登录", kind = TagKind.Warn) }) },
        floatingNav = true,
    ) {
        Isle {
            PopHead(
                "需要登录 tailnet",
                sub = "登录之前代理用不了：所有出口都经 tailnet 出去。",
                tile = { Tile(Ms.error, TileTone.Butter) },
            )
            ShoalCard(Modifier.padding(top = 12.dp)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    // 登录链接本身是数据，原样显示；超长时末尾省略
                    if (ui.ready) CssText(ui.loginURL, mono(12f, FontWeight.Normal, 16f), c.tx)
                    else CssText("正在获取登录链接…", ui(12f, lineHeight = 16f), c.tx2)
                }
            }
            Foot("手机上多半打不开登录页（第三方账号登录要走代理，而代理还没通）。复制到电脑浏览器里批准，批准后这里自动变成已连接。")
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OlBtn("复制链接", a.copyLogin, Modifier.weight(1f), icon = Ms.contentCopy, enabled = ui.ready)
                MiniBtn("浏览器打开", a.openLogin, Modifier.weight(1f), size = MiniSize.Medium, leadingIcon = Ms.openInNew, enabled = ui.ready)
            }
        }
        Gap(12.dp)
        Isle(padding = 6.dp) {
            ListRow(
                ui.hostname, sub = "本机",
                leading = { Tile(Ms.smartphone, TileTone.Mint) },
                trailing = { Tag("等待批准", kind = TagKind.Warn) },
            )
        }
    }
}

@Composable
private fun osIcon(os: OsKind) = when (os) {
    OsKind.Phone -> Ms.smartphone
    OsKind.Mac -> Ms.laptopMac
    OsKind.Windows -> Ms.desktopWindows
    OsKind.Server -> Ms.dns
}

@Composable
private fun TailnetRunning(ui: TailnetUi.Running, a: PetrelActions) {
    val c = Shoal.colors
    var confirmLogout by remember { mutableStateOf(false) }
    val sum = ui.summary
    ScreenFrame(
        appBar = {
            ShoalAppBar(
                "tailnet",
                titleTrailing = { Tag(ui.statusLabel, kind = if (ui.statusTone == StatusTone.Live) TagKind.Live else TagKind.Warn) },
            )
        },
        floatingNav = true,
    ) {
        Isle {
            PopHead(
                ui.selfName ?: "—",
                sub = "本机 · 已登录 tailnet",
                tile = { Tile(Ms.smartphone, TileTone.Mint) },
                trailing = {
                    ShoalIconButton(Ms.contentCopy, "复制地址", { ui.selfIp?.let(a.copyAddress) }, iconSize = 20.dp, enabled = ui.selfIp != null)
                },
            )
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.sf2).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Kv("本机地址", ui.selfIp ?: "—", Modifier.weight(1f))
                Kv("在线", sum?.ratioSpaced ?: "—", Modifier.weight(1f))
                Kv("路径", sum?.pathLabel ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniBtn("登出", { confirmLogout = true }, size = MiniSize.Medium, leadingIcon = Ms.logout)
                CssText("登出后要在电脑浏览器里重新批准", ui(11.5f), c.tx2, Modifier.weight(1f))
            }
        }
        // isle 的 margin-bottom 12 与 `.lbl` 的 margin-top 16 折叠成 16
        Gap(16.dp)
        Lbl("节点", em = sum?.onlineLabel ?: "—")
        Isle(padding = 6.dp) {
            val peers = ui.peers
            when {
                peers == null -> EmptyNote("—")
                peers.isEmpty() -> EmptyNote("tailnet 里还没有其它节点")
                else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    peers.forEach { p ->
                        ListRow(
                            p.name, sub = p.ip ?: "—", subTabular = true,
                            icon = osIcon(p.os), iconBox = 22.dp,
                            titleColor = if (p.offline) c.tx2 else null,
                            // 没有 IPv4 的 peer 没有可复制的地址：不给长按动作
                            onLongClick = p.ip?.let { ip -> { a.copyAddress(ip) } },
                            onLongClickLabel = "复制地址",
                            trailing = { LatBadge(p.path, p.band, tabular = false) },
                        )
                    }
                }
            }
        }
        Foot("长按节点复制地址。直连是点对点 WireGuard，中继经 DERP 转发。", top = 12.dp)
    }
    if (confirmLogout) {
        LogoutDialog(onConfirm = { confirmLogout = false; a.logout() }, onDismiss = { confirmLogout = false })
    }
}

@Composable
private fun EmptyNote(text: String) {
    CssText(text, ui(13.5f), Shoal.colors.tx2, Modifier.padding(horizontal = 10.dp, vertical = 12.dp))
}

/** `.kv` 的一格：`dt` 10.5 字距 .04em `--tx2`，`dd` 12.5/500 tnum、上距 3。 */
@Composable
private fun Kv(key: String, value: String, modifier: Modifier = Modifier) {
    val c = Shoal.colors
    Column(modifier) {
        CssText(key, ui(10.5f, letterSpacing = 0.04.em), c.tx2, softWrap = false)
        CssText(value, ui(12.5f, FontWeight.Medium).tabular(), c.tx, Modifier.padding(top = 3.dp), softWrap = false)
    }
}

// =====================================================================
// 配置
// =====================================================================

@Composable
internal fun ShoalConfig(ui: ConfigUi, a: PetrelActions) {
    val c = Shoal.colors
    ScreenFrame(appBar = { ShoalAppBar("配置", onBack = a.back) }, floatingNav = false) {
        ui.error?.let { err ->
            Isle(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                PopHead(err.title, tile = { Tile(Ms.error, TileTone.Pink) })
                ShoalCard(Modifier.padding(top = 12.dp)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                        CssLines(err.box, mono(12f, FontWeight.Normal, 18f), c.tx)
                    }
                }
                if (err.hint.isNotEmpty()) Foot(err.hint)
            }
            Gap(12.dp)
        }
        Isle {
            val cfg = ui.config
            PopHead(
                cfg.name,
                sub = ui.timeLine,
                tile = { Tile(Ms.description, TileTone.Butter) },
            )
            // 只有经界面导入、校验过的配置才有这一行；push-config.sh 推入的没有元数据
            if (ui.verified) Tag("校验通过", Modifier.padding(top = 10.dp), kind = TagKind.Live)
        }
        Gap(12.dp)
        OlBtn(
            ui.importLabel, a.importConfig,
            Modifier.fillMaxWidth(), icon = Ms.download, enabled = !ui.importing,
        )
        Foot("先校验，通过才替换现有配置；VPN 运行中会自动重启生效。", side = 6.dp)
        Gap(12.dp)
        Isle(padding = 6.dp) {
            ListRow(
                "GeoIP 数据库", sub = "Country.mmdb · ${ui.geoText}", subColor = if (ui.geoBad) c.redFg else c.tx2,
                leading = { Tile(Ms.public, TileTone.Mint) },
                trailing = { MiniBtn(ui.geoButtonLabel, a.replaceGeoIp, enabled = !ui.geoBusy) },
            )
        }
        Gap(12.dp)
        Isle {
            PopHead("加载时 Petrel 会改写这些", sub = "配置里不用写", tile = { Tile(Ms.tune, TileTone.Brown) })
            ShoalCard(Modifier.padding(top = 12.dp)) {
                ui.rewrites.forEach { ListRow(it.key, sub = it.desc, subWrap = true) }
            }
        }
    }
}

// =====================================================================
// 设置
// =====================================================================

@Composable
internal fun ShoalSettings(ui: SettingsUi, a: PetrelActions) {
    var licenses by remember { mutableStateOf(false) }
    ScreenFrame(appBar = { ShoalAppBar("设置", onBack = a.back) }, floatingNav = false) {
        Isle {
            PopHead("外观", tile = { Tile(Ms.palette, TileTone.Brown) })
            val inner = PaddingValues(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp)
            // 只列已经实现的皮肤；只有一套时整行不显示
            if (ui.skins.size > 1) {
                Lbl("皮肤", padding = inner)
                Seg(ui.skins, ui.skin, a.setSkin, label = { it.title })
            }
            Lbl("明暗", padding = inner)
            Seg(UiTone.entries, ui.tone, a.setTone, label = { it.title })
        }
        Gap(12.dp)
        Isle(padding = 6.dp) {
            ListRow(
                "配置", sub = ui.configName,
                leading = { Tile(Ms.description, TileTone.Butter) },
                onClick = a.openConfig, role = Role.Button,
                trailing = { RowEnd(icon = Ms.chevronRight) },
            )
        }
        Gap(12.dp)
        Isle {
            PopHead("关于", tile = { Tile(Ms.info, TileTone.Sage) })
            ShoalCard(Modifier.padding(top = 12.dp)) {
                ListRow("版本", trailing = { RowEnd("v${ui.versionName}") })
                ListRow(
                    "开源许可", onClick = { licenses = true }, role = Role.Button,
                    trailing = { RowEnd(icon = Ms.chevronRight) },
                )
            }
        }
    }
    if (licenses) LicensesDialog(onDismiss = { licenses = false })
}
