package com.robb3n.petrel.ui.skin.tonal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robb3n.petrel.ui.longPressOnly
import com.robb3n.petrel.ui.model.Band
import com.robb3n.petrel.ui.model.breakAfterHyphens
import com.robb3n.petrel.ui.pressHighlight
import com.robb3n.petrel.ui.skin.CssLines
import com.robb3n.petrel.ui.skin.CssText
import com.robb3n.petrel.ui.skin.tabular
import com.robb3n.petrel.ui.theme.PlexMono

/*
 * Tonal 组件原语。每个原语照画稿同名 class 取值，注释里的 `.item` 等即对应的 CSS 选择器，
 * 数值（px = dp、字号 px = sp）原样搬过来，不另立档位。
 */

/** 画稿的文本样式：系统默认字体（spec 决策 7），指定字号 / 字重 / 行高（px = sp）。行高缺省是 `.phone` 的 1.4。 */
fun tui(size: Float, weight: FontWeight = FontWeight.Normal, lineHeight: Float? = null, letterSpacing: TextUnit = TextUnit.Unspecified) =
    TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (lineHeight ?: (size * 1.4f)).sp,
        letterSpacing = letterSpacing,
    )

/** `var(--mono)` = IBM Plex Mono（节点名、IP、延迟等数据）。 */
fun tmono(size: Float, weight: FontWeight = FontWeight.Normal, lineHeight: Float? = null) =
    TextStyle(
        fontFamily = PlexMono,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (lineHeight ?: (size * 1.4f)).sp,
    )

// ---------------- 顶栏 ----------------

/** `.tbar`：高 64；一级页 `padding:0 4 0 20`，标题 `h1` 28/500/1.2；二级页左边是 48 的返回键。 */
@Composable
fun TonalAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    end: Dp = 4.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Tonal.colors
    Row(
        modifier.fillMaxWidth().height(64.dp).padding(start = if (onBack != null) 4.dp else 20.dp, end = end),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) IconBtn(Ms4.arrowBack, "返回", onBack)
        CssText(title, tui(28f, FontWeight.Medium, 33.6f), c.on, Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** `.icb` / `.icb2`：48 的圆形图标键，图标 24。 */
@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    spin: Boolean = false,
) {
    val c = Tonal.colors
    // 匀速旋转 .9s/圈；只在 [spin] 时才起动画
    val angle: State<Float>? = if (spin) {
        rememberInfiniteTransition(label = "iconSpin")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    } else {
        null
    }
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description,
            tint = (tint ?: c.onV).let { if (enabled || spin) it else it.copy(alpha = 0.38f) },
            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = angle?.value ?: 0f },
        )
    }
}

// ---------------- 分组 ----------------

/** `.ghead`：主色小标题，`min-height:36; padding:0 4 4`，500 14/20。[spread] = 尾部控件靠右（`.sp`），否则紧跟标题（节点页的 `select · N`）。 */
@Composable
fun GroupHead(title: String, modifier: Modifier = Modifier, spread: Boolean = true, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = Tonal.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CssText(title, tui(14f, FontWeight.Medium, 20f), c.pri)
        if (spread) Spacer(Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** `.tlink`：文字链接，`min-height:40; padding:0 8`，500 14 主色。 */
@Composable
fun TextLink(text: String, onClick: () -> Unit) {
    val c = Tonal.colors
    Box(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { CssText(text, tui(14f, FontWeight.Medium), c.pri, softWrap = false) }
}

/** `.chipm`：高 24、`padding:0 8`、圆角 8、1 的 `--outV` 内描边，500 12 `--onV`。 */
@Composable
fun MetaChip(text: String) {
    val c = Tonal.colors
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.height(24.dp).border(1.dp, c.outV, shape).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        CssText(text, tui(12f, FontWeight.Medium), c.onV, softWrap = false)
    }
}

/** `.gmeta`：500 12 `--onV`。 */
@Composable
fun GroupMeta(text: String) {
    CssText(text, tui(12f, FontWeight.Medium), Tonal.colors.onV, softWrap = false)
}

/** `.foot`：`margin:8 4 16`，12.5/1.5 `--onV`。外边距由调用方给。 */
@Composable
fun Foot(text: String, modifier: Modifier = Modifier) {
    CssLines(text, tui(12.5f, lineHeight = 18.75f), Tonal.colors.onV, modifier.padding(horizontal = 4.dp))
}

// ---------------- .item ----------------

enum class Pos { First, Mid, Last, Only }

fun posOf(index: Int, count: Int): Pos = when {
    count <= 1 -> Pos.Only
    index == 0 -> Pos.First
    index == count - 1 -> Pos.Last
    else -> Pos.Mid
}

/** `.item.first` 22/22/6/6、`.item.last` 6/6/22/22、其余 6；只有一行时四角都是 22。 */
fun Pos.shape(): Shape = when (this) {
    Pos.First -> RoundedCornerShape(22.dp, 22.dp, 6.dp, 6.dp)
    Pos.Mid -> RoundedCornerShape(6.dp)
    Pos.Last -> RoundedCornerShape(6.dp, 6.dp, 22.dp, 22.dp)
    Pos.Only -> RoundedCornerShape(22.dp)
}

/**
 * 一组 `.item`：行间距 2（`margin-bottom:2`，末行 0），首 / 末行大圆角。[content] 里每行用 [Item]，位置由 [posOf] 给。
 */
@Composable
fun ItemGroup(count: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.(pos: (Int) -> Pos) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        content { posOf(it, count) }
    }
}

/**
 * `.item`：列表行（`min-height:68; padding:10 16; gap:14; --sfC` 底）。[selected] = `.item.sel`（`--priC` 底、`--onPriC` 字）。
 * 行首 [leading]（多为 [Avatar]）、主文 + 副文、行尾 [trailing]。
 */
@Composable
fun Item(
    pos: Pos,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    minHeight: Dp = 68.dp,
    role: Role? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClickLabel: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Tonal.colors
    val shape = pos.shape()
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(if (selected) c.priC else c.sfC)
        .then(if (role == Role.RadioButton) Modifier.semantics { this.selected = selected } else Modifier)
    val clickable = when {
        onClick != null -> base.pressHighlight(onClickLabel, role = role, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, onClick = onClick)
        // 行不可点、只有长按：不带 click 语义（见 longPressOnly）
        onLongClick != null -> base.longPressOnly(onLongClickLabel, onLongClick)
        else -> base
    }
    Row(
        clickable.heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), content = content)
        trailing?.invoke(this)
    }
}

/** `.item` 里的主文 + 副文：`b` 等宽 14.5/500/1.3，`small` 13 `--onV`、上距 2（`sel` 行是 `--onPriC` 78%）。 */
@Composable
fun ItemText(
    title: String,
    sub: String? = null,
    selected: Boolean = false,
    faded: Boolean = false,
    mono: Boolean = true,
    subMono: Boolean = false,
    subWrap: Boolean = false,
    subTabular: Boolean = false,
    subColor: Color? = null,
    /** 主文允许折到两行（当前出口的长节点名）：连字符后换行，其余仍单行省略。 */
    titleWrap: Boolean = false,
) {
    val c = Tonal.colors
    val titleStyle = if (mono) tmono(14.5f, FontWeight.Medium, 18.85f) else tui(14.5f, FontWeight.Medium, 18.85f)
    val titleTint = if (selected) c.onPriC else if (faded) c.onV else c.on
    if (titleWrap) CssLines(title.breakAfterHyphens(), titleStyle, titleTint, maxLines = 2) else CssText(title, titleStyle, titleTint)
    if (sub != null) {
        val style = (if (subMono) tmono(12.5f, FontWeight.Normal, 17.5f) else tui(13f, lineHeight = 18.2f)).let { if (subTabular) it.tabular() else it }
        val color = subColor ?: if (selected) c.onPriC.copy(alpha = 0.78f) else c.onV
        if (subWrap) CssLines(sub, style, color, Modifier.padding(top = 2.dp)) else CssText(sub, style, color, Modifier.padding(top = 2.dp))
    }
}

/** `.av`：40 的圆形头像，图标 22；[primary] = `.av.pri`（`--pri` 底）；默认 `--sfHH` / `--onV`，[bg] / [fg] 可换。 */
@Composable
fun Avatar(icon: ImageVector, primary: Boolean = false, bg: Color? = null, fg: Color? = null) {
    val c = Tonal.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(bg ?: if (primary) c.pri else c.sfHH),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = fg ?: if (primary) c.onPri else c.onV, modifier = Modifier.size(22.dp)) }
}

/** 头像里放文字（首次使用的步骤序号）。 */
@Composable
fun TextAvatar(text: String, bg: Color? = null, fg: Color? = null) {
    val c = Tonal.colors
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg ?: c.sfHH), contentAlignment = Alignment.Center) {
        CssText(text, tui(16f, FontWeight.Medium, 20f), fg ?: c.onV, softWrap = false)
    }
}

// ---------------- .badge ----------------

/** `.badge` 的底色与字色：Ok `--okC`、Warn `--warnC`、Bad error container（spec §2.7）、Idle `--sfHH`。 */
fun bandColors(c: TonalColors, band: Band): Pair<Color, Color> = when (band) {
    Band.Ok -> c.okC to c.onOkC
    Band.Warn -> c.warnC to c.onWarnC
    Band.Bad -> c.errC to c.onErrC
    Band.Idle -> c.sfHH to c.onV
}

/** `.badge`：`padding:7 10`、全圆角，500 12/1。 */
@Composable
fun Badge(text: String, band: Band, tabular: Boolean = true) {
    val (bg, fg) = bandColors(Tonal.colors, band)
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 7.dp)) {
        CssText(text, tui(12f, FontWeight.Medium, 12f).let { if (tabular) it.tabular() else it }, fg, softWrap = false)
    }
}

// ---------------- 按钮 ----------------

/** `.fbtn`：实心按钮（高 40、`padding:0 20 0 16`、圆角 20、`--pri` 底，500 14、图标 18、间距 8）。[fill] = 整宽。 */
@Composable
fun FilledBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fill: Boolean = false,
) {
    val c = Tonal.colors
    val bg = if (enabled) c.pri else c.on.copy(alpha = 0.12f)
    val fg = if (enabled) c.onPri else c.on.copy(alpha = 0.38f)
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = if (icon != null) 16.dp else 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        CssText(text, tui(14f, FontWeight.Medium), fg, softWrap = false)
    }
}

/** `.obtn`：描边按钮（同 `.fbtn` 的尺寸；1 的描边，字色 [color]、描边取字色 40%）。 */
@Composable
fun OutlineBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = Tonal.colors.pri,
    borderColor: Color? = null,
    fill: Boolean = false,
) {
    val fg = if (enabled) color else Tonal.colors.on.copy(alpha = 0.38f)
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .height(40.dp)
            .clip(shape)
            .border(1.dp, borderColor ?: if (enabled) color.copy(alpha = 0.4f) else Tonal.colors.on.copy(alpha = 0.12f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = if (icon != null) 16.dp else 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        CssText(text, tui(14f, FontWeight.Medium), fg, softWrap = false)
    }
}

/** `.efab`：扩展 FAB（高 56、`padding:0 20 0 16`、圆角 16、`--priC` 底，500 16、图标 24、间距 12，M3 3 级阴影）。 */
@Composable
fun ExtendedFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, spin: Boolean = false) {
    val c = Tonal.colors
    val shape = RoundedCornerShape(16.dp)
    val rotation = if (spin) {
        rememberInfiniteTransition(label = "fabSpin")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "fabRot")
    } else {
        null
    }
    Row(
        modifier
            .shadow(6.dp, shape)
            .clip(shape)
            .background(c.priC)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .height(56.dp)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon, null, tint = c.onPriC,
            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation?.value ?: 0f },
        )
        CssText(text, tui(16f, FontWeight.Medium, 22.4f), c.onPriC, softWrap = false)
    }
}

// ---------------- 分段控件 ----------------

/** M3 Segmented Button：高 40、全圆角、1 的 `--outV` 描边，选中段 `--secC` 底 `--onSecC` 字并带 18 的对勾（spec §2.8）。 */
@Composable
fun <T> TonalSeg(options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String, modifier: Modifier = Modifier) {
    val c = Tonal.colors
    val shape = RoundedCornerShape(50)
    Row(modifier.fillMaxWidth().height(40.dp).clip(shape).border(1.dp, c.outV, shape)) {
        options.forEachIndexed { i, opt ->
            val on = opt == selected
            val bg by animateColorAsState(if (on) c.secC else Color.Transparent, tween(150), label = "segBg")
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .drawBehind {
                        drawRect(bg)
                        if (i > 0) drawRect(c.outV, Offset.Zero, Size(1.dp.toPx(), size.height))
                    }
                    .clickable(role = Role.RadioButton, onClickLabel = label(opt)) { onSelect(opt) }
                    .semantics { this.selected = on },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (on) Icon(Ms4.check, null, tint = c.onSecC, modifier = Modifier.size(18.dp))
                CssText(label(opt), tui(14f, FontWeight.Medium), if (on) c.onSecC else c.on, softWrap = false)
            }
        }
    }
}

/** 居中的提示（节点页未运行、tailnet 页 VPN 未连接）。 */
@Composable
fun CenterNote(title: String, sub: String? = null, modifier: Modifier = Modifier) {
    val c = Tonal.colors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CssText(title, if (sub == null) tui(14f) else tui(16f, FontWeight.Medium, 22.4f), if (sub == null) c.onV else c.on)
            if (sub != null) CssText(sub, tui(14f), c.onV)
        }
    }
}
