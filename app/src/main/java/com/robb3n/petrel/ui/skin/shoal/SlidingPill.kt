package com.robb3n.petrel.ui.skin.shoal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInParent
import kotlin.math.floor
import kotlin.math.min

/*
 * 悬浮底栏（`.fnav`）的**滑动选中胶囊**（移植自 Mu3ic ui/shoal/SlidingPill.kt 的 PillTrack / drawPill）：
 * 选中底色是一整块胶囊，位置就是 pager 的实时位置（见 `TabPager`），在各项之间滑动；手指按住胶囊横拖，pager 跟着翻页。
 *
 * 用法：各项 `Modifier.onPlaced { track.place(i, it) }` 量几何；行在内边距**之后**挂 `drawBehind { drawPill(…) }`
 * 与拖动手势——画和拖用的都是行内坐标，与各项 positionInParent 同一把尺。
 */

/** 各项在行内的左缘 / 宽度（px，量出来的）。位置 `pos` 是项序号、可带小数：落在两项之间时左缘与宽度线性插值。 */
@Stable
class PillTrack(val count: Int) {
    private val lefts = mutableStateListOf<Float>().apply { repeat(count) { add(Float.NaN) } }
    private val widths = mutableStateListOf<Float>().apply { repeat(count) { add(Float.NaN) } }

    fun place(index: Int, coords: LayoutCoordinates) {
        if (index !in 0 until count) return
        val x = coords.positionInParent().x
        val w = coords.size.width.toFloat()
        if (lefts[index] != x) lefts[index] = x
        if (widths[index] != w) widths[index] = w
    }

    /** 各项都量到了（首帧布局前还没有）。 */
    val isMeasured: Boolean get() = lefts.none { it.isNaN() } && widths.none { it.isNaN() }

    fun leftAt(pos: Float): Float = lerpAt(lefts, pos)

    fun widthAt(pos: Float): Float = lerpAt(widths, pos)

    private fun lerpAt(v: List<Float>, pos: Float): Float {
        val p = pos.coerceIn(0f, (count - 1).toFloat())
        val i = floor(p).toInt()
        val j = min(i + 1, count - 1)
        return v[i] + (v[j] - v[i]) * (p - i)
    }

    private fun center(i: Int): Float = lefts[i] + widths[i] / 2f

    /** 相邻两项中心的平均间距（px）：手指横移这么多 = 胶囊挪一项。还没量完时给 1，免得除零。 */
    fun itemSpan(): Float =
        if (!isMeasured || count < 2) 1f else ((center(count - 1) - center(0)) / (count - 1)).coerceAtLeast(1f)
}

/** 在位置 [pos] 画胶囊（行高、全圆角）；还没量完就不画。 */
fun DrawScope.drawPill(track: PillTrack, pos: Float, color: Color) {
    if (!track.isMeasured || pos.isNaN()) return
    drawRoundRect(
        color = color,
        topLeft = Offset(track.leftAt(pos), 0f),
        size = Size(track.widthAt(pos), size.height),
        cornerRadius = CornerRadius(size.height / 2f),
    )
}
