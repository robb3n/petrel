package com.robb3n.petrel.ui.skin.shoal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.robb3n.petrel.ui.bounceClick
import com.robb3n.petrel.ui.longPressOnly
import com.robb3n.petrel.ui.model.Band
import com.robb3n.petrel.ui.model.breakAfterHyphens
import com.robb3n.petrel.ui.pressHighlight
import com.robb3n.petrel.ui.theme.PlexMono
import com.robb3n.petrel.ui.skin.CssText
import com.robb3n.petrel.ui.skin.CssLines
import com.robb3n.petrel.ui.skin.cssLines
import com.robb3n.petrel.ui.skin.tabular

/*
 * Shoal 组件原语。每个原语照画稿同名 class 取值，注释里的 `.isle` 等即对应的 CSS 选择器，
 * 数值（px = dp、字号 px = sp）原样搬过来，不另立档位。写法与坑见 Mu3ic docs/lessons/shoal-mockup-porting.md。
 */

/** `border-radius:999px`。 */
val PillShape = RoundedCornerShape(50)

/** 画稿的文本样式：Rubik，指定字号 / 字重 / 行高（px = sp）。行高缺省是 body 的 1.35。 */
fun ui(size: Float, weight: FontWeight = FontWeight.Normal, lineHeight: Float? = null, letterSpacing: TextUnit = TextUnit.Unspecified) =
    TextStyle(
        fontFamily = Rubik,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (lineHeight ?: (size * 1.35f)).sp,
        letterSpacing = letterSpacing,
    )

/** 打包的 IBM Plex Mono 取代画稿 `--mono`（JetBrains Mono），见 spec §2.5。 */
fun mono(size: Float, weight: FontWeight = FontWeight.Medium, lineHeight: Float? = null, letterSpacing: TextUnit = TextUnit.Unspecified) =
    TextStyle(
        fontFamily = PlexMono,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (lineHeight ?: (size * 1.35f)).sp,
        letterSpacing = letterSpacing,
    )

// ---------------- 阴影（--shIsle / --shPill 近似） ----------------

/** `--shIsle`：elevation 投影 + 暖棕 ambient / spot 色近似，外圈 0.5dp 描边照抄。深色下投影为黑、描边为暖白 7%。 */
fun Modifier.isleSurface(c: ShoalColors, shape: Shape, color: Color = c.sf, elevation: Dp = 8.dp): Modifier =
    this
        .shadow(elevation, shape, clip = false, ambientColor = c.shadow, spotColor = c.shadow)
        .clip(shape)
        .background(color)
        .border(0.5.dp, c.isleRing, shape)

/** `--shPill`。 */
fun Modifier.pillSurface(c: ShoalColors, shape: Shape, color: Color = c.sf): Modifier =
    this
        .shadow(2.dp, shape, clip = false, ambientColor = c.shadow, spotColor = c.shadow)
        .clip(shape)
        .background(color)
        .border(0.5.dp, c.pillRing, shape)

// ---------------- .isle / .ph / .card ----------------

private val IsleShape = RoundedCornerShape(24.dp)

/** `.isle`：浮岛卡（`--sf` 底、24 圆角、`--shIsle`、内边距 14；`.isle.tight` 内边距 6）。外边距由调用方给。 */
@Composable
fun Isle(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Shoal.colors
    Column(modifier.fillMaxWidth().isleSurface(c, IsleShape).padding(padding), content = content)
}

/** `.ph`：卡头（tile + 标题 15/600/1.25 + 说明 12/1.4 `--tx2` + 尾部控件，间距 10）。 */
@Composable
fun PopHead(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    tile: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Shoal.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tile?.invoke()
        Column(Modifier.weight(1f)) {
            CssText(title, ui(15f, FontWeight.SemiBold, 18.75f), c.tx)
            if (sub != null) CssText(sub, ui(12f, lineHeight = 16.8f), c.tx2, Modifier.padding(top = 2.dp))
        }
        trailing?.invoke(this)
    }
}

/** `.card`：卡内块（`--sf2` 底、16 圆角、内边距 6）；内部 `.list` 行距 2。 */
@Composable
fun ShoalCard(
    modifier: Modifier = Modifier,
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Shoal.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color ?: c.sf2).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

// ---------------- .row ----------------

private val RowShape = RoundedCornerShape(14.dp)

/**
 * `.row`：列表行（内边距 8/10、14 圆角、间距 10）。主文 13.5/500，副文 11.5 `--tx2`；
 * [selected] = `.row.sel`（`--butter` 底、图标与主文转 `--butterInk`）。[icon] 是行首 19dp 图标，[leading] 可换成 tile 等任意块。
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    /** `.row>.ms` 的固定宽度（tailnet 页的设备图标 `width:22; text-align:center`）。 */
    iconBox: Dp? = null,
    titleColor: Color? = null,
    /** 主文允许折到两行（当前出口的长节点名）：连字符后换行，其余仍单行省略。 */
    titleWrap: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    subTabular: Boolean = false,
    subColor: Color? = null,
    /** 副文允许折行（首次使用的步骤说明）。 */
    subWrap: Boolean = false,
    role: Role? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Shoal.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(if (selected) c.butter else Color.Transparent)
            .then(if (role == Role.RadioButton) Modifier.semantics { this.selected = selected } else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.pressHighlight(
                        onClickLabel = onClickLabel ?: title,
                        role = role,
                        onLongClick = onLongClick,
                        onLongClickLabel = onLongClickLabel,
                        onClick = onClick,
                    )
                } else if (onLongClick != null) {
                    // 行不可点、只有长按：不带 click 语义（见 longPressOnly）
                    Modifier.longPressOnly(onLongClickLabel, onLongClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leading != null) {
            leading()
        } else if (icon != null) {
            val tint = iconTint ?: if (selected) c.butterInk else c.tx2
            if (iconBox != null) {
                Box(Modifier.width(iconBox), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp)) }
            } else {
                Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            val titleStyle = ui(13.5f, FontWeight.Medium)
            val titleTint = titleColor ?: if (selected) c.butterInk else c.tx
            if (titleWrap) CssLines(title.breakAfterHyphens(), titleStyle, titleTint, maxLines = 2) else CssText(title, titleStyle, titleTint)
            if (sub != null) {
                val subStyle = ui(11.5f).let { if (subTabular) it.tabular() else it }
                if (subWrap) CssLines(sub, subStyle, subColor ?: c.tx2, Modifier.padding(top = 1.dp))
                else CssText(sub, subStyle, subColor ?: c.tx2, Modifier.padding(top = 1.dp))
            }
        }
        trailing?.invoke(this)
    }
}

/** `.row .end`：行尾说明（12.5 `--tx2`、tnum）+ 可选 18dp 图标（多为 chevron_right），间距 1。 */
@Composable
fun RowEnd(text: String? = null, icon: ImageVector? = null, textColor: Color? = null) {
    val c = Shoal.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        if (text != null) CssText(text, ui(12.5f).tabular(), textColor ?: c.tx2, softWrap = false)
        if (icon != null) Icon(icon, null, tint = c.tx2, modifier = Modifier.size(18.dp))
    }
}

// ---------------- .tag / .lat ----------------

enum class TagKind { Plain, Live, Warn }

/** `.tag`（10.5/500/1、`padding:4 7`、胶囊、间距 4；Plain `--sf2`/`--tx2`，Live 薄荷 + 6dp 绿点，Warn 黄油）。 */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, kind: TagKind = TagKind.Plain) {
    val c = Shoal.colors
    val (bg, fg) = when (kind) {
        TagKind.Plain -> c.sf2 to c.tx2
        TagKind.Live -> c.mint to c.mintInk
        TagKind.Warn -> c.butter to c.butterInk
    }
    Row(
        modifier.clip(PillShape).background(bg).padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (kind == TagKind.Live) Box(Modifier.size(6.dp).clip(CircleShape).background(c.liveDot))
        CssText(text, ui(10.5f, FontWeight.Medium, 10.5f), fg, softWrap = false)
    }
}

/** `.lat`：延迟 / 路径徽章（12/500/1、`padding:4 8`；ok 薄荷、warn 黄油、bad 粉、idle `--sf3`）。 */
@Composable
fun LatBadge(text: String, band: Band, modifier: Modifier = Modifier, tabular: Boolean = true) {
    val c = Shoal.colors
    val (bg, fg) = when (band) {
        Band.Ok -> c.mint to c.mintInk
        Band.Warn -> c.butter to c.butterInk
        Band.Bad -> c.pink to c.pinkInk
        Band.Idle -> c.sf3 to c.tx2
    }
    val style = ui(12f, FontWeight.Medium, 12f).let { if (tabular) it.tabular() else it }
    Box(modifier.clip(PillShape).background(bg).padding(horizontal = 8.dp, vertical = 4.dp)) {
        CssText(text, style, fg, softWrap = false)
    }
}

// ---------------- .lbl / .foot ----------------

/**
 * `.lbl`：小标题（11/600/1、字距 .08em、`--tx2`；Petrel 画稿 `margin:16 6 8`）。[em] 是跟在后面的 `<em>` 段
 * （等宽 10.5/500、`--em` 色、字距 .02em）。
 */
@Composable
fun Lbl(
    text: String,
    modifier: Modifier = Modifier,
    em: String? = null,
    padding: PaddingValues = PaddingValues(start = 6.dp, end = 6.dp, top = 0.dp, bottom = 8.dp),
) {
    val c = Shoal.colors
    Row(
        modifier.fillMaxWidth().padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CssText(text, ui(11f, FontWeight.SemiBold, 11f, letterSpacing = 0.08.em), c.tx2, softWrap = false)
        if (em != null) CssText(em, mono(10.5f, FontWeight.Medium, 10.5f, letterSpacing = 0.02.em), c.em, softWrap = false)
    }
}

/** `.foot`：说明文字（11.5、行高 1.5、`--tx2`）。左右外边距 [side] 画稿里是 6（连接页）或 4（节点、tailnet 页）。 */
@Composable
fun Foot(text: String, modifier: Modifier = Modifier, side: Dp = 4.dp, top: Dp = 10.dp) {
    Text(
        text,
        style = ui(11.5f, lineHeight = 17.25f).cssLines(),
        color = Shoal.colors.tx2,
        modifier = modifier.fillMaxWidth().padding(start = side, end = side, top = top),
    )
}

// ---------------- .tile ----------------

enum class TileTone {
    Brown, Ol, Sage, Butter, Mint, Pink;

    fun colors(c: ShoalColors): Pair<Color, Color> = when (this) {
        Brown -> c.brown to c.brownInk
        Ol -> c.ol to c.onol
        Sage -> c.sage to c.sageInk
        Butter -> c.butter to c.butterInk
        Mint -> c.mint to c.mintInk
        Pink -> c.pink to c.pinkInk
    }
}

/** `.tile`：36dp 圆形图标块，20dp 图标；`.t-ol / .t-butter / .t-mint / .t-sage / .t-brown`。 */
@Composable
fun Tile(icon: ImageVector, tone: TileTone, modifier: Modifier = Modifier) {
    val (bg, fg) = tone.colors(Shoal.colors)
    Box(modifier.size(36.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
    }
}

/** 同 [Tile]，里面放一个字（首次使用的步骤序号）。 */
@Composable
fun TextTile(text: String, tone: TileTone, modifier: Modifier = Modifier) {
    val (bg, fg) = tone.colors(Shoal.colors)
    Box(modifier.size(36.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        CssText(text, ui(15f, FontWeight.SemiBold, 15f), fg, softWrap = false)
    }
}

// ---------------- .ib ----------------

/** `.ib`：40dp 圆形图标钮（`--tx2`、图标 [iconSize]；按压 `--sf2` 底，画在图标之下）。 */
@Composable
fun ShoalIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    tint: Color? = null,
    enabled: Boolean = true,
    spin: Boolean = false,
) {
    val c = Shoal.colors
    // 匀速旋转 .9s/圈；只在 [spin] 时才起动画
    val angle: State<Float>? = if (spin) {
        rememberInfiniteTransition(label = "iconSpin")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    } else {
        null
    }
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .pressHighlight(onClickLabel = label, enabled = enabled, role = Role.Button, highlight = c.sf2, behind = true, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val fg = tint ?: c.tx2
        Icon(icon, null, tint = if (enabled || spin) fg else fg.copy(alpha = 0.38f), modifier = Modifier.size(iconSize).graphicsLayer { rotationZ = angle?.value ?: 0f })
    }
}

// ---------------- .mini / .obtn ----------------

enum class MiniSize { Small, Medium, OnWall }

/**
 * `.mini`：小胶囊按钮。Small = 连接页（26 高、12/500、16dp 图标、`padding:0 10 0 8`；`.mini.end` 带尾图标时 `0 6 0 10`）；
 * Medium = tailnet 页（30 高、12.5/500、17dp、`0 12 0 10`）；OnWall = 节点页 `.mini.onwall`（32 高、`--sf` 底 + `--shPill`）。
 */
@Composable
fun MiniBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: MiniSize = MiniSize.Small,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    spin: Boolean = false,
) {
    val c = Shoal.colors
    val h = when (size) { MiniSize.Small -> 26.dp; MiniSize.Medium -> 30.dp; MiniSize.OnWall -> 32.dp }
    val fontSize = if (size == MiniSize.Small) 12f else 12.5f
    val iconSize = if (size == MiniSize.Small) 16.dp else if (size == MiniSize.Medium) 17.dp else 17.dp
    val face = if (size == MiniSize.OnWall) Modifier.pillSurface(c, PillShape) else Modifier.clip(PillShape).background(c.sf2)
    val fg = if (enabled) c.tx else c.tx2
    val start = when {
        trailingIcon != null && leadingIcon == null -> 10.dp
        size == MiniSize.Small -> 8.dp
        else -> 10.dp
    }
    val end = if (trailingIcon != null) 6.dp else if (size == MiniSize.Small) 10.dp else 12.dp
    // 匀速旋转 .9s/圈；只在测速中才起动画
    val angle: State<Float>? = if (spin) {
        rememberInfiniteTransition(label = "miniSpin")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    } else {
        null
    }
    Row(
        modifier
            .bounceClick(onClickLabel = text, enabled = enabled, role = Role.Button, onClick = onClick)
            .height(h)
            .then(face)
            .padding(start = start, end = end),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        leadingIcon?.let {
            Icon(it, null, tint = fg, modifier = Modifier.size(iconSize).graphicsLayer { rotationZ = angle?.value ?: 0f })
        }
        CssText(text, ui(fontSize, FontWeight.Medium, fontSize * 1.35f), fg, softWrap = false)
        trailingIcon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(iconSize)) }
    }
}

/**
 * `.obtn`：橄榄棕实心胶囊（30 高、`--ol` 底、`--onol` 字 12.5/500、18dp 图标、`padding:0 12 0 9`）。
 * [onHero] = `.hero .obtn`（`#FFF6E0` 底、`#705A0F` 字）。
 */
@Composable
fun OlBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onHero: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Shoal.colors
    val bg = if (!enabled) c.sf3 else if (onHero) Color(0xFFFFF6E0) else c.ol
    val fg = if (!enabled) c.tx2 else if (onHero) Color(0xFF705A0F) else c.onol
    Row(
        modifier
            .bounceClick(onClickLabel = text, enabled = enabled, role = Role.Button, onClick = onClick)
            .height(30.dp)
            .clip(PillShape)
            .background(bg)
            .padding(start = if (icon == null) 12.dp else 9.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(18.dp)) }
        CssText(text, ui(12.5f, FontWeight.Medium, 16.9f), fg, softWrap = false)
    }
}

// ---------------- .sw ----------------

private val SwEasing = CubicBezierEasing(0.3f, 0.9f, 0.3f, 1f)
private val PulseEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

enum class SwStyle { Plain, Hero }

/**
 * `.sw`：开关（42×24 胶囊，关 `--sf3` / 开 `--ol`；16dp 圆钮，关时 `--knob`、开时右移 18 并转 `--onol`；
 * 钮位移 `.22s cubic-bezier(.3,.9,.3,1)`，底色 `.2s`）。[busy] = `.sw.busy`：钮透明度 1 ↔ 0.35 往复（`.7s` ease-in-out alternate）。
 * [SwStyle.Hero] = 状态卡渐变上的 `.hero .sw`：关 `rgba(255,246,224,.24)` / 钮 `.75`，开 `#FFF6E0` / 钮 `#705A0F`。
 */
@Composable
fun Sw(
    checked: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    style: SwStyle = SwStyle.Plain,
) {
    val c = Shoal.colors
    val (trackOn, trackOff, knobOn, knobOff) = when (style) {
        SwStyle.Plain -> listOf(c.ol, c.sf3, c.onol, c.knob)
        SwStyle.Hero -> listOf(Color(0xFFFFF6E0), Color(0x3DFFF6E0), Color(0xFF705A0F), Color(0xBFFFF6E0))
    }
    val track by animateColorAsState(if (checked) trackOn else trackOff, tween(200), label = "swTrack")
    val knob by animateColorAsState(if (checked) knobOn else knobOff, tween(200), label = "swKnob")
    val x by animateDpAsState(if (checked) 18.dp else 0.dp, tween(220, easing = SwEasing), label = "swX")
    val pulseAlpha: State<Float>? = if (busy) {
        rememberInfiniteTransition(label = "swPulse")
            .animateFloat(1f, 0.35f, infiniteRepeatable(tween(700, easing = PulseEasing), RepeatMode.Reverse), label = "alpha")
    } else {
        null
    }
    Box(
        modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(PillShape)
            .background(track)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClickLabel = label,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                toggleableState = ToggleableState(checked)
            },
    ) {
        Box(
            Modifier
                .offset(x = 4.dp + x, y = 4.dp)
                .size(16.dp)
                .graphicsLayer { alpha = pulseAlpha?.value ?: 1f }
                .clip(CircleShape)
                .background(knob),
        )
    }
}

// ---------------- .seg ----------------

private val SegSlide = spring<Float>(dampingRatio = 0.82f, stiffness = 420f)

/**
 * `.seg`：胶囊分段（`--sf2` 轨、内边距 3、间距 2；项 30 高、`padding:0 10`、12.5/500；选中 `--ol` 底 `--onol` 字）。
 * 选中底是一整块滑动胶囊。各项等宽（画稿 `.seg button{flex:1}`）。每项带 `selected` 语义。
 */
@Composable
fun <T> Seg(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val c = Shoal.colors
    val index = options.indexOf(selected).coerceAtLeast(0)
    val pos = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) { pos.animateTo(index.toFloat(), SegSlide) }
    val density = LocalDensity.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(c.sf2)
            .padding(3.dp)
            .drawBehind {
                val gap = with(density) { 2.dp.toPx() }
                val n = options.size
                val w = (size.width - gap * (n - 1)) / n
                drawRoundRect(
                    color = c.ol,
                    topLeft = Offset(pos.value * (w + gap), 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val fg by animateColorAsState(if (on) c.onol else c.tx2, tween(150), label = "segFg")
            val text = label(option)
            Box(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(PillShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        role = Role.Tab,
                        onClickLabel = text,
                    ) { onSelect(option) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                CssText(text, ui(12.5f, FontWeight.Medium, 16f), fg, softWrap = false)
            }
        }
    }
}

// ---------------- .appbar ----------------

/**
 * `.appbar`：56 高、间距 8、`padding:0 [end] 0 18`（连接 / tailnet 页右 8，节点页右 14）；标题 600 20/1.2、字距 .01em。
 * [titleTrailing] 紧跟在标题后（连接徽章：`<h1>…</h1><span class="tag">` + `.sp` 撑开），[actions] 贴右。
 * 二级页传 [onBack]：返回键在最前，左边距缩到 8（Shoal 的返回钮约定，同 Mu3ic）。
 * 标题与尾随件收进同一个 `weight(1f)` 的 Row（= `.sp` 撑开），别写成「标题 weight + Spacer weight」，见 lessons。
 */
@Composable
fun ShoalAppBar(
    title: String,
    modifier: Modifier = Modifier,
    end: Dp = 8.dp,
    onBack: (() -> Unit)? = null,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Shoal.colors
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(start = if (onBack != null) 8.dp else 18.dp, end = end),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) ShoalIconButton(Ms.arrowBack, "返回", onBack)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CssText(title, ui(20f, FontWeight.SemiBold, 24f, letterSpacing = 0.01.em), c.tx, Modifier.weight(1f, fill = false))
            titleTrailing?.invoke(this)
        }
        actions()
    }
}

/** 占位：竖向空隙，写成 Spacer 方便按画稿的 margin 折叠值落一处。 */
@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))
