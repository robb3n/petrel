package com.robb3n.petrel.ui.skin.night

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.robb3n.petrel.ui.PetrelIcons
import com.robb3n.petrel.ui.bounceClick
import com.robb3n.petrel.ui.model.Band
import com.robb3n.petrel.ui.model.DelayReadout
import com.robb3n.petrel.ui.model.Tab
import com.robb3n.petrel.ui.skin.TabPager
import com.robb3n.petrel.ui.skin.tabCloseness
import com.robb3n.petrel.ui.pressHighlight
import com.robb3n.petrel.ui.skin.CssLines
import com.robb3n.petrel.ui.skin.CssText
import com.robb3n.petrel.ui.skin.cssLines
import com.robb3n.petrel.ui.skin.tabular
import com.robb3n.petrel.ui.theme.PlexMono

/*
 * 夜航组件原语。每个原语照画稿同名 class 取值，注释里的 `.panel` 等即对应的 CSS 选择器，
 * 数值（px = dp、字号 px = sp）原样搬过来。CSS 的 `box-sizing: border-box` + 1px 描边：内容离外沿 = 描边 + padding，
 * Compose 的 `border()` 不占位，所以带描边的盒子里 padding 要加 1。
 */

val PanelShape = RoundedCornerShape(14.dp)

private fun style(family: FontFamily, size: Float, weight: FontWeight, lineHeight: Float?, letterSpacing: TextUnit, defaultLine: Float) =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (lineHeight ?: (size * defaultLine)).sp,
        letterSpacing = letterSpacing,
    )

/** `var(--ui)` = IBM Plex Sans；行高缺省是 body 的 1.4。 */
fun nui(size: Float, weight: FontWeight = FontWeight.Normal, lineHeight: Float? = null, letterSpacing: TextUnit = TextUnit.Unspecified) =
    style(PlexSans, size, weight, lineHeight, letterSpacing, 1.4f)

/** `var(--disp)` = Barlow Condensed（只有 500 / 600）。 */
fun ndisp(size: Float, weight: FontWeight = FontWeight.Medium, lineHeight: Float? = null, letterSpacing: TextUnit = TextUnit.Unspecified) =
    style(BarlowCondensed, size, weight, lineHeight, letterSpacing, 1.4f)

/** `var(--mono)` = IBM Plex Mono。 */
fun nmono(size: Float, weight: FontWeight = FontWeight.Normal, lineHeight: Float? = null, letterSpacing: TextUnit = TextUnit.Unspecified) =
    style(PlexMono, size, weight, lineHeight, letterSpacing, 1.4f)

// ---------------- 颜色 ----------------

/** 延迟 / 路径的着色档：`--ok` / `--warn` / `--err` / `--ink3`。 */
@Composable
fun bandColor(band: Band): Color {
    val c = Night.colors
    return when (band) {
        Band.Ok -> c.ok
        Band.Warn -> c.warn
        Band.Bad -> c.err
        Band.Idle -> c.ink3
    }
}

// ---------------- .panel / 分隔线 ----------------

/** `.panel`：`--p1` 底、1 描边 `--line`、14 圆角。外边距由调用方给（画稿 12 或 10）。[outline] 换描边色（导入失败横幅用 `--err`）。 */
@Composable
fun Panel(modifier: Modifier = Modifier, outline: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Night.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(c.p1)
            .border(1.dp, outline ?: c.line, PanelShape)
            .padding(1.dp),
        content = content,
    )
}

/** 行间 / 区块间的 1 分隔线（`border-top: 1px solid var(--line)`）。 */
@Composable
fun HLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Night.colors.line))
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

// ---------------- .cap / .ghead / .foot ----------------

/** `.cap`：中文小标 11/500 字距 .06em + 等宽英文小标 9.5/500 字距 .16em，`--ink3`，间距 6。 */
@Composable
fun Cap(zh: String, en: String? = null, modifier: Modifier = Modifier) {
    val c = Night.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CssText(zh, nui(11f, FontWeight.Medium, 11f, 0.06.em), c.ink3, softWrap = false)
        if (en != null) CssText(en, nmono(9.5f, FontWeight.Medium, 9.5f, 0.16.em), c.ink3, softWrap = false)
    }
}

/**
 * `.ghead`：组标题（基线对齐，间距 8；`.gname` Barlow 600 18/1 字距 .06em；`.mono.t3` 等宽 11 `--ink3`；右端 `.t3` 11 `--ink3`）。
 * 外边距 `margin: top 6 8`，上边距各页不同（节点页 12、tailnet 页 14），由 [top] 给。
 * 行盒高 19.8：gname 的基线在顶下 16.2，11px 的小字行盒 15.4、基线在其顶下 11.8，所以小字从 4.4 起排。
 * 不用 Compose 的基线对齐：gname 行高小于字体自然高度，行盒会被撑高。
 */
@Composable
fun GHead(
    name: String,
    modifier: Modifier = Modifier,
    en: String? = null,
    right: String? = null,
    top: Dp = 12.dp,
) {
    val c = Night.colors
    Row(
        modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = top, bottom = 8.dp).height(19.8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CssText(name, ndisp(18f, FontWeight.SemiBold, 18f, 0.06.em), c.ink, softWrap = false)
        if (en != null) CssText(en, nmono(11f, FontWeight.Normal, 15.4f), c.ink3, Modifier.padding(top = 4.4.dp), softWrap = false)
        Spacer(Modifier.weight(1f))
        if (right != null) CssText(right, nui(11f, FontWeight.Normal, 15.4f), c.ink3, Modifier.padding(top = 4.4.dp), softWrap = false)
    }
}

/** `.foot`：说明文字（11.5、行高 1.5、`--ink3`），左右外边距 6。 */
@Composable
fun Foot(text: String, modifier: Modifier = Modifier, top: Dp = 0.dp, bottom: Dp = 0.dp) {
    Text(
        text,
        style = nui(11.5f, lineHeight = 17.25f).cssLines(),
        color = Night.colors.ink3,
        modifier = modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = top, bottom = bottom),
    )
}

// ---------------- 顶栏 ----------------

/** `.icb`：圆角 10 的方形图标钮（`--ink2`；按压 `--p2` 底，画在图标之下）。连接页 44 / 图标 20，tailnet 复制钮 40 / 图标 17。 */
@Composable
fun IconBtn(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    box: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    spin: Boolean = false,
) {
    val c = Night.colors
    // 匀速旋转 .9s/圈；只在 [spin] 时才起动画
    val angle: State<Float>? = if (spin) {
        rememberInfiniteTransition(label = "iconSpin")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    } else {
        null
    }
    Box(
        modifier
            .size(box)
            .clip(RoundedCornerShape(10.dp))
            .pressHighlight(onClickLabel = label, enabled = enabled, role = Role.Button, highlight = c.p2, behind = true, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled || spin) c.ink2 else c.ink3, modifier = Modifier.size(iconSize).graphicsLayer { rotationZ = angle?.value ?: 0f })
    }
}

/**
 * `.top` + `.title`：60 高、间距 8、右 12（tailnet 页 16）、左 20；标题 Barlow 600 28/1 字距 .03em。
 * 二级页传 [onBack]：返回键在最前（44 方钮），左边距缩到 6。[trailing] 贴右。
 */
@Composable
fun TitleBar(
    title: String,
    modifier: Modifier = Modifier,
    end: Dp = 12.dp,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Night.colors
    Row(
        modifier.fillMaxWidth().height(60.dp).padding(start = if (onBack != null) 6.dp else 20.dp, end = end),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) IconBtn(PetrelIcons.Back, "返回", onBack)
        CssText(title, ndisp(28f, FontWeight.SemiBold, 28f, 0.03.em), c.ink, Modifier.weight(1f), softWrap = false)
        trailing()
    }
}

// ---------------- .chip ----------------

/** 连接页顶栏的描边 chip（`TUN · gvisor`）：24 高、`padding:0 9`、1 `--line` 描边、6 圆角、等宽 11/500 `--ink2`。 */
@Composable
fun OutlineChip(text: String, modifier: Modifier = Modifier) {
    val c = Night.colors
    Box(
        modifier.height(24.dp).border(1.dp, c.line, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        CssText(text, nmono(11f, FontWeight.Medium, 11f), c.ink2, softWrap = false)
    }
}

enum class ChipTone { Ok, Warn, Idle }

/** `.chip.ok` / 留意色同形：24 高、`padding:0 9`、6 圆角、`--okBg` 底 `--ok` 字（或 warn）、11/500、6 圆点、间距 6。 */
@Composable
fun StatusChip(text: String, tone: ChipTone, modifier: Modifier = Modifier, dot: Boolean = true) {
    val c = Night.colors
    val (bg, fg) = when (tone) {
        ChipTone.Ok -> c.okBg to c.ok
        ChipTone.Warn -> c.warnBg to c.warn
        ChipTone.Idle -> c.p2 to c.ink2
    }
    Row(
        modifier.height(24.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        CssText(text, nui(11f, FontWeight.Medium, 11f), fg, softWrap = false)
    }
}

// ---------------- 按钮 ----------------

/** `.obtn`：描边按钮（36 高、`--p1` 底、1 `--line` 描边、8 圆角、`padding:0 12`、13/500 `--ink`、16 图标、间距 6）。 */
@Composable
fun OutlineBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    spin: Boolean = false,
) {
    val c = Night.colors
    val fg = if (enabled) c.ink else c.ink3
    val angle: State<Float>? = if (spin) {
        rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    } else {
        null
    }
    Row(
        modifier
            .bounceClick(onClickLabel = text, enabled = enabled, role = Role.Button, onClick = onClick)
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.p1)
            .border(1.dp, c.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = angle?.value ?: 0f }) }
        CssText(text, nui(13f, FontWeight.Medium, 13f), fg, softWrap = false)
    }
}

/** `.ghost`：整宽描边按钮（44 高、1 `--line` 描边、10 圆角、14/500、18 图标、间距 8）。 */
@Composable
fun GhostBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val c = Night.colors
    val fg = if (enabled) c.ink else c.ink3
    Row(
        modifier
            .fillMaxWidth()
            .bounceClick(onClickLabel = text, enabled = enabled, role = Role.Button, pressedScale = 0.98f, onClick = onClick)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, c.line, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(18.dp)) }
        CssText(text, nui(14f, FontWeight.Medium, 14f), fg, softWrap = false)
    }
}

/** 实心主按钮（spec §2.8）：`--ink` 底、`--bg` 字、8 圆角、44 高、14/500、16 图标、间距 8。 */
@Composable
fun SolidBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = Night.colors
    val bg = if (enabled) c.ink else c.line
    val fg = if (enabled) c.bg else c.ink3
    Row(
        modifier
            .bounceClick(onClickLabel = text, enabled = enabled, role = Role.Button, pressedScale = 0.98f, onClick = onClick)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(16.dp)) }
        CssText(text, nui(14f, FontWeight.Medium, 14f), fg, softWrap = false)
    }
}

/** `.wbtn`：留意色按钮（36 高、`padding:0 12`、8 圆角、`--warnBg` 底 `--warn` 字 13/500、15 图标、间距 6）。 */
@Composable
fun WarnBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Night.colors
    Row(
        modifier
            .bounceClick(onClickLabel = text, role = Role.Button, onClick = onClick)
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.warnBg)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CssText(text, nui(13f, FontWeight.Medium, 13f), c.warn, softWrap = false)
        icon?.let { Icon(it, null, tint = c.warn, modifier = Modifier.size(15.dp)) }
    }
}

// ---------------- 分段控件（spec §2.8） ----------------

/**
 * 分段控件：`.panel` 底、1 `--line` 描边、8 圆角、内边距 3；项 32 高、6 圆角、13/500；选中段 `--ink` 底 `--bg` 字。
 * 各项等宽，每项带 `selected` 语义。
 */
@Composable
fun <T> NightSeg(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val c = Night.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.p1)
            .border(1.dp, c.line, RoundedCornerShape(8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val bg by animateColorAsState(if (on) c.ink else Color.Transparent, tween(150), label = "segBg")
            val fg by animateColorAsState(if (on) c.bg else c.ink2, tween(150), label = "segFg")
            val text = label(option)
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClickLabel = text,
                    ) { onSelect(option) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                CssText(text, nui(13f, FontWeight.Medium, 13f), fg, softWrap = false)
            }
        }
    }
}

// ---------------- .led / .pwr ----------------

enum class LedKind { Live, Busy, Warn, Off }

private val BlinkEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * `.led`：10 圆点。live `--ok` + 4 的 `--okBg` 外圈；busy `--ink2`、无外圈、透明度 1 ↔ .3 往复（`.7s` ease-in-out）；
 * warn `--warn` + `--warnBg` 外圈；off 透明底、1.5 `--ink3` 描边。
 */
@Composable
fun Led(kind: LedKind, modifier: Modifier = Modifier) {
    val c = Night.colors
    val blink: State<Float>? = if (kind == LedKind.Busy) {
        rememberInfiniteTransition(label = "blink").animateFloat(1f, 0.3f, infiniteRepeatable(tween(700, easing = BlinkEasing), RepeatMode.Reverse), label = "alpha")
    } else {
        null
    }
    val fill = when (kind) {
        LedKind.Live -> c.ok
        LedKind.Busy -> c.ink2
        LedKind.Warn -> c.warn
        LedKind.Off -> Color.Transparent
    }
    val ring = when (kind) {
        LedKind.Live -> c.okBg
        LedKind.Warn -> c.warnBg
        else -> null
    }
    Box(
        modifier
            .size(10.dp)
            .graphicsLayer { alpha = blink?.value ?: 1f }
            // box-shadow 的外圈只画在盒子外面（不压在圆点底下）：用描边圆环
            .drawBehind { if (ring != null) drawCircle(ring, radius = size.minDimension / 2f + 2.dp.toPx(), style = Stroke(4.dp.toPx())) }
            .clip(CircleShape)
            .background(fill)
            .then(if (kind == LedKind.Off) Modifier.border(1.5.dp, c.ink3, CircleShape) else Modifier),
    )
}

/**
 * `.pwr`：64 圆形电源键，1.5 描边、26 图标（线宽 2）。on（live / busy / warn）= `--ok` 描边与图标、`--okBg` 底、外圈 7 的 `--glow`；
 * off = `--line` 描边、`--ink2` 图标、透明底、无光晕。
 */
@Composable
fun PowerKey(on: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Night.colors
    val border = if (on) c.ok else c.line
    Box(
        modifier
            .size(64.dp)
            .bounceClick(onClickLabel = label, enabled = enabled, role = Role.Switch, onClick = onClick)
            // 外圈（box-shadow 0 0 0 7）只在盒子外面，里面的 okBg 不叠两层
            .drawBehind { if (on) drawCircle(c.glow, radius = size.minDimension / 2f + 3.5.dp.toPx(), style = Stroke(7.dp.toPx())) }
            .clip(CircleShape)
            .background(if (on) c.okBg else Color.Transparent)
            .border(1.5.dp, border, CircleShape)
            .semantics {
                contentDescription = label
                toggleableState = ToggleableState(on)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(PetrelIcons.PowerHeavy, null, tint = if (on) c.ok else c.ink2, modifier = Modifier.size(26.dp))
    }
}

// ---------------- 行 ----------------

/**
 * `.lrow`：列表行（内边距 14 / 12 / 14 / 16、间距 12）。主文 14/500/1.3，副文 11.5/1.3 `--ink2`（[subMono] 默认等宽，
 * 说明性文字传 false 用界面字）；[leading] 常是 20 的 `--ink2` 图标。
 */
@Composable
fun LRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    subMono: Boolean = true,
    subWrap: Boolean = false,
    subColor: Color? = null,
    titleColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    role: Role? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Night.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressHighlight(onClickLabel = onClickLabel ?: title, role = role, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            CssText(title, nui(14f, FontWeight.Medium, 18.2f), titleColor ?: c.ink)
            if (sub != null) {
                val subStyle = if (subMono) nmono(11.5f, FontWeight.Normal, 14.95f) else nui(11.5f, FontWeight.Normal, 14.95f)
                if (subWrap) CssLines(sub, subStyle, subColor ?: c.ink2, Modifier.padding(top = 3.dp))
                else CssText(sub, subStyle, subColor ?: c.ink2, Modifier.padding(top = 3.dp))
            }
        }
        trailing?.invoke(this)
    }
}

/** `.lrow>.ic`：行首 20 图标。 */
@Composable
fun RowIcon(icon: ImageVector) {
    Icon(icon, null, tint = Night.colors.ink2, modifier = Modifier.size(20.dp))
}

/** `.val`：行尾读数（等宽 11.5/500，默认 `--ink2`）。 */
@Composable
fun Val(text: String, color: Color? = null) {
    CssText(text, nmono(11.5f, FontWeight.Medium, 16.1f), color ?: Night.colors.ink2, softWrap = false)
}

/** `.lrow .chev`：行尾 16 的 `--ink3` 箭头。 */
@Composable
fun Chev() {
    Icon(PetrelIcons.Chevron, null, tint = Night.colors.ink3, modifier = Modifier.size(16.dp))
}

// ---------------- .meter / .msv / .dotk ----------------

/** `.meter`：4 格信号（宽 3、间距 2、高 5 / 8 / 11 / 14、1 圆角、底色 `--line`，点亮的格按档位着色）。 */
@Composable
fun Meter(filled: Int, band: Band, modifier: Modifier = Modifier) {
    val c = Night.colors
    val on = bandColor(band)
    Row(modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(5.dp, 8.dp, 11.dp, 14.dp).forEachIndexed { i, h ->
            Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(1.dp)).background(if (i < filled) on else c.line))
        }
    }
}

/**
 * `.msv`：节点行的延迟大数字（Barlow 500 24/1 tnum、右对齐、最小宽 52）；单位 `ms` 是等宽 10/500 `--ink2`，
 * 渲染图里排在数字下面。没有单位（测速中、超时、未测）时也占住这一行（15），否则这些行比有数值的行矮、列表会跳。
 * [color] 由调用方按 [DelayReadout.tone] 选：Idle `--ink3`（`.msv.idle`）、Bad `--err`、其余 `--ink`。
 */
@Composable
fun Msv(readout: DelayReadout, color: Color, modifier: Modifier = Modifier) {
    val c = Night.colors
    Column(modifier.widthIn(min = 52.dp), horizontalAlignment = Alignment.End) {
        // 「超时」是汉字，24 的 Barlow 字号放在行里太抢眼，缩到 18（画稿没画这个状态）
        CssText(readout.value, ndisp(if (readout.timeout) 18f else 24f, FontWeight.Medium, 24f).tabular(), color, softWrap = false)
        if (readout.unit.isNotEmpty()) CssText(readout.unit, nmono(10f, FontWeight.Medium, 15f), c.ink2, softWrap = false)
        else Spacer(Modifier.height(15.dp))
    }
}

/** `.dotk`：8 圆点，在线实心 `--ok`，否则 1.5 `--ink3` 空心。tailnet 页的 peer 点直接用，节点页的底座点带 5 的横向外边距。 */
@Composable
fun StatusDot(on: Boolean, modifier: Modifier = Modifier) {
    val c = Night.colors
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .then(if (on) Modifier.background(c.ok) else Modifier.border(1.5.dp, c.ink3, CircleShape)),
    )
}

/** `.path`：peer 的路径徽章（11.5/500/1、`padding:5 8`、6 圆角；ok / warn 着色底，idle 是 1 `--line` 内描边）。 */
@Composable
fun PathBadge(text: String, band: Band, modifier: Modifier = Modifier) {
    val c = Night.colors
    val shape = RoundedCornerShape(6.dp)
    val (fg, bg) = when (band) {
        Band.Ok -> c.ok to c.okBg
        Band.Warn -> c.warn to c.warnBg
        Band.Bad -> c.err to Color.Transparent
        Band.Idle -> c.ink3 to Color.Transparent
    }
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (band == Band.Idle) Modifier.border(1.dp, c.line, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        CssText(text, nui(11.5f, FontWeight.Medium, 11.5f), fg, softWrap = false)
    }
}

// ---------------- .dock ----------------

private class DockItem(val tab: Tab, val label: String, val icon: ImageVector)

/**
 * `.dock`：贴底底栏（高 64 + 手势条 inset、`--p1` 底、顶部 1 `--line`、三列等宽）。选中项文字 `--ink`，顶上一条 32×2 的 `--ok` 指示条（压在顶描边上）。
 * 指示条的位置就是 pager 的实时位置（[TabPager.position]），翻页时跟着滑；各项字色按离指示条多近在 `--ink3` 与 `--ink` 之间渐变。
 */
@Composable
fun NightDock(tabs: TabPager, modifier: Modifier = Modifier) {
    val c = Night.colors
    val items = listOf(
        DockItem(Tab.Home, "连接", PetrelIcons.Power),
        DockItem(Tab.Nodes, "节点", PetrelIcons.Nodes),
        DockItem(Tab.Tailnet, "tailnet", PetrelIcons.Tailnet),
    )
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pos = tabs.position
    Column(modifier.fillMaxWidth().background(c.p1)) {
        HLine()
        Row(
            Modifier
                .fillMaxWidth()
                .height(63.dp)
                .drawBehind {
                    val itemW = size.width / items.size
                    val cx = (tabs.position + 0.5f) * itemW
                    val w = 32.dp.toPx()
                    val r = 2.dp.toPx()
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = cx - w / 2f, top = -1.dp.toPx(),
                                right = cx + w / 2f, bottom = 1.dp.toPx(),
                                topLeftCornerRadius = CornerRadius.Zero, topRightCornerRadius = CornerRadius.Zero,
                                bottomLeftCornerRadius = CornerRadius(r), bottomRightCornerRadius = CornerRadius(r),
                            ),
                        )
                    }
                    drawPath(path, c.ok)
                },
        ) {
            items.forEachIndexed { i, item ->
                val fg = lerp(c.ink3, c.ink, tabCloseness(pos, i))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClickLabel = item.label,
                        ) { tabs.select(item.tab) }
                        .semantics { selected = tabs.current == item.tab },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                ) {
                    Icon(item.icon, null, tint = fg, modifier = Modifier.size(22.dp))
                    CssText(item.label, nui(11f, FontWeight.Medium, 11f), fg, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(inset))
    }
}

/** 居中的两行提示（节点页未运行、tailnet 页 VPN 未连接）。 */
@Composable
fun CenterNote(title: String, sub: String? = null, modifier: Modifier = Modifier) {
    val c = Night.colors
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CssText(title, if (sub == null) nui(14f) else nui(16f, FontWeight.Medium), if (sub == null) c.ink3 else c.ink)
            if (sub != null) CssText(sub, nui(13f), c.ink3)
        }
    }
}
