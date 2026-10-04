package com.robb3n.petrel.ui.skin

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/*
 * 按 CSS 行盒排字的原语，各皮肤共用（Shoal、夜航）。写法与坑见 Mu3ic docs/lessons/shoal-mockup-porting.md。
 */

/**
 * 多行文字按 CSS 行盒排：每行占满 lineHeight、字形在行内垂直居中，首行上方、末行下方的 leading 不裁
 * （Compose 默认的 `Trim.Both` 会把这两处裁掉，整段比 CSS 矮一截）。单行文字用 [CssText]。
 */
fun TextStyle.cssLines(): TextStyle =
    copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))

/** `font-variant-numeric: tabular-nums`。 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * 单行文字按 CSS 行盒占位：高 = style 的 lineHeight，字形在里面垂直居中、可以溢出行盒。
 * Compose 的 lineHeight 只会撑高、压不到字体自然高度以下（Oswald 数字、回落的中文字体都会比 CSS 高），
 * 所以放在布局层做。
 */
@Composable
fun CssText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    softWrap: Boolean = true,
) {
    val box = with(LocalDensity.current) { style.lineHeight.toDp() }
    Text(text, style = style, color = color, maxLines = maxLines, overflow = overflow, softWrap = softWrap, modifier = modifier.cssLineBox(box))
}

fun Modifier.cssLineBox(lineHeight: Dp): Modifier =
    height(lineHeight).wrapContentHeight(Alignment.CenterVertically, unbounded = true)

/** 会折行的文字按 CSS 行盒总占位：高 = 行数 × lineHeight，字形整体居中。 */
fun Modifier.cssLinesBox(lineHeight: Dp, lines: () -> Int): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val h = lineHeight.roundToPx() * lines().coerceAtLeast(1)
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

/**
 * 会折行的文字：按 CSS 行盒总占位（见 [cssLinesBox]），行数取自实际排版。
 * 超过 [maxLines] 行时末尾省略（当前出口的长节点名用 2 行，见 `docs/spec/skins.md`）。
 */
@Composable
fun CssLines(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    var lines by remember { mutableIntStateOf(1) }
    val box = with(LocalDensity.current) { style.lineHeight.toDp() }
    Text(
        text,
        style = style.cssLines(),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { lines = it.lineCount },
        modifier = modifier.cssLinesBox(box) { lines },
    )
}

