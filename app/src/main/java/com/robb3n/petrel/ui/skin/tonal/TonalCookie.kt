package com.robb3n.petrel.ui.skin.tonal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.robb3n.petrel.ui.model.ConnStatus
import com.robb3n.petrel.ui.model.StatusTone
import com.robb3n.petrel.ui.model.tone
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 饼干形大按钮的外形：`r(θ) = R + a·cos(9θ)`，画稿 viewBox 200、中心 (100,100)，θ 从正上方顺时针（spec §2.9）。
 * 已连接 / 待登录 / 连接中 R = 91、a = 9（饼干形），未连接 R = 94、a = 0（圆）。
 */
const val COOKIE_LOBES = 9
private const val COOKIE_POINTS = 180

/** 外形参数：状态切换时 R 与 a 一起插值。 */
data class CookieShape(val r: Float, val a: Float) {
    companion object {
        val Cookie = CookieShape(91f, 9f)
        val Circle = CookieShape(94f, 0f)
    }
}

/** 画稿 viewBox（200）下 θ 处的半径。 */
fun cookieRadius(shape: CookieShape, theta: Double): Float = shape.r + shape.a * cos(COOKIE_LOBES * theta).toFloat()

/** 外形每隔这个角度重复一次（360° / 瓣数）：旋转停在它的整数倍上，看起来与没转过一样。 */
const val COOKIE_STEP_DEGREES = 360f / COOKIE_LOBES

/** 离 [angle] 最近的 [COOKIE_STEP_DEGREES] 整数倍。 */
fun settledAngle(angle: Float): Float = (angle / COOKIE_STEP_DEGREES).roundToInt() * COOKIE_STEP_DEGREES

/** 按公式画出外形 Path，坐标已缩放到 [sizePx]（viewBox 200 → sizePx）。半径取自 [cookieRadius]，测的就是画的。 */
fun cookiePath(r: Float, a: Float, sizePx: Float): Path {
    val k = sizePx / 200f
    val shape = CookieShape(r, a)
    val path = Path()
    for (i in 0 until COOKIE_POINTS) {
        val theta = 2 * PI * i / COOKIE_POINTS
        val rad = cookieRadius(shape, theta)
        val x = (100f + rad * sin(theta).toFloat()) * k
        val y = (100f - rad * cos(theta).toFloat()) * k
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private val CookieEasing = CubicBezierEasing(0.3f, 0.9f, 0.3f, 1f)

private enum class CookieKind { Primary, Busy, Warn, Off }

private fun cookieKind(s: ConnStatus) = when (s.tone) {
    StatusTone.Live -> CookieKind.Primary
    StatusTone.Busy -> CookieKind.Busy
    StatusTone.Warn -> CookieKind.Warn
    StatusTone.Idle -> CookieKind.Off
}

/**
 * `.ck`：176 的饼干形电源键。形状按 [cookiePath] 在 `.5s cubic-bezier(.3,.9,.3,1)` 内向目标 R / a 插值，填充色 `.3s` 过渡；
 * 连接中整个形状匀速旋转（5 s/圈），图标不转。动画值只在 draw / graphicsLayer lambda 里读（见 Mu3ic android-compose-animation.md）。
 */
@Composable
fun CookieButton(status: ConnStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tonal.colors
    val kind = cookieKind(status)
    val shape = if (kind == CookieKind.Off) CookieShape.Circle else CookieShape.Cookie
    val r = remember { Animatable(shape.r) }
    val a = remember { Animatable(shape.a) }
    LaunchedEffect(shape) {
        // R 与 a 同时开始、同一条曲线，外形整体平滑变形
        coroutineScope {
            launch { r.animateTo(shape.r, tween(500, easing = CookieEasing)) }
            launch { a.animateTo(shape.a, tween(500, easing = CookieEasing)) }
        }
    }
    val spin = remember { Animatable(0f) }
    LaunchedEffect(kind == CookieKind.Busy) {
        if (kind == CookieKind.Busy) {
            while (true) {
                spin.snapTo(0f)
                spin.animateTo(360f, tween(5000, easing = LinearEasing))
            }
        } else {
            // 离开「连接中」时不能直接归零：形状每 40° 重复一次，当前角度离最近的 40° 倍数最多差 20°，归零会突然跳一下。
            // 沿用形变的曲线（.5s cubic-bezier(.3,.9,.3,1)）转到最近的整倍数再停
            val target = settledAngle(spin.value)
            if (target != spin.value) spin.animateTo(target, tween(500, easing = CookieEasing))
            spin.snapTo(target % 360f) // 整倍数，外形不变；别让角度无限累积
        }
    }
    val fill = animateColorAsState(
        when (kind) {
            CookieKind.Primary -> c.pri
            CookieKind.Busy -> c.priC
            CookieKind.Warn -> c.warnC
            CookieKind.Off -> c.sfHH
        },
        tween(300), label = "ckFill",
    )
    val iconColor = animateColorAsState(
        when (kind) {
            CookieKind.Primary -> c.onPri
            CookieKind.Busy -> c.onPriC
            CookieKind.Warn -> c.onWarnC
            CookieKind.Off -> c.onV
        },
        tween(300), label = "ckIcon",
    )
    val on = kind != CookieKind.Off
    val enabled = status != ConnStatus.NoConfig
    Box(
        modifier
            .size(176.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick)
            .semantics {
                contentDescription = if (on) "断开 VPN" else "连接 VPN"
                toggleableState = if (on) ToggleableState.On else ToggleableState.Off
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(176.dp)
                .graphicsLayer { rotationZ = spin.value }
                .drawBehind { drawPath(cookiePath(r.value, a.value, size.width), fill.value) },
        )
        Icon(Ms4.powerSettingsNewFill, null, tint = iconColor.value, modifier = Modifier.size(60.dp))
    }
}
