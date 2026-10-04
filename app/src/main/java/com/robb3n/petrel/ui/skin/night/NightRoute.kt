package com.robb3n.petrel.ui.skin.night

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.robb3n.petrel.ui.model.HopRole
import com.robb3n.petrel.ui.model.Station
import com.robb3n.petrel.ui.skin.CssText

/** 航线图的两种样子：[Live] 已连接（实线流动）、[Quiet] 待登录 / tailnet 异常（静止虚线，tailnet 站点变留意色）。 */
enum class RouteMode { Live, Quiet }

private val RouteHeight = 44.dp

/**
 * `.route`：航线图，高 44。站点 = 本机 + 每一跳，等距排开（`left: i/(n-1)`，各自 `translateX(-50%)`，头尾站点压在两端）。
 * 轨道 `.track` 在 top:7、高 2：Live 是 8 实 / 6 空的 `--ok` 虚线，以每 .9s 14dp 的速度流向出口；
 * Quiet 是 4 / 4 的 `--line` 静止虚线。站点 `.stop`：16 圆（2 描边）+ 9 + 标签 11/500 `--ink2`；
 * 末站（出口）Live 时实心 `--ok` + 5 的 `--okBg` 外圈。
 */
@Composable
fun RouteMap(stations: List<Station>, mode: RouteMode, modifier: Modifier = Modifier) {
    val c = Night.colors
    val live = mode == RouteMode.Live
    // 动画值只在 draw lambda 里读，帧间不触发重组；Quiet 不起动画
    val flow: State<Float>? = if (live) {
        rememberInfiniteTransition(label = "flow").animateFloat(0f, 14f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "offset")
    } else {
        null
    }
    Layout(
        content = {
            stations.forEachIndexed { i, s ->
                Stop(s.label, last = i == stations.lastIndex, tailnet = s.role == HopRole.Tailnet, live = live)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(RouteHeight)
            .drawBehind {
                val on = if (live) 8.dp.toPx() else 4.dp.toPx()
                val off = if (live) 6.dp.toPx() else 4.dp.toPx()
                val period = on + off
                // 背景位置向右移 = 虚线图案向右流动 = DashPathEffect 的相位向左减
                val phase = period - ((flow?.value ?: 0f).dp.toPx() % period)
                val y = 8.dp.toPx()
                drawLine(
                    color = if (live) c.ok else c.line,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Butt,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, off), phase),
                )
            },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val w = constraints.maxWidth
        val n = placeables.size
        layout(w, RouteHeight.roundToPx()) {
            placeables.forEachIndexed { i, p ->
                val x = if (n <= 1) 0 else (w.toLong() * i / (n - 1)).toInt()
                p.place(x - p.width / 2, 0)
            }
        }
    }
}

@Composable
private fun Stop(label: String, last: Boolean, tailnet: Boolean, live: Boolean) {
    val c = Night.colors
    // Live：描边 ok、底 p1、末站实心 ok + 外圈；Quiet：描边 ink3、末站空心，tailnet 站点描边 warn、底 warnBg
    val border = when {
        live -> c.ok
        tailnet -> c.warn
        else -> c.ink3
    }
    val fill: Color = when {
        live && last -> c.ok
        !live && tailnet -> c.warnBg
        else -> c.p1
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            Modifier
                .size(16.dp)
                .drawBehind { if (live && last) drawCircle(c.okBg, radius = size.minDimension / 2f + 2.5.dp.toPx(), style = Stroke(5.dp.toPx())) }
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, border, CircleShape),
        )
        CssText(label, nui(11f, FontWeight.Medium, 11f), c.ink2, softWrap = false)
    }
}
