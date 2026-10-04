package com.robb3n.petrel.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/*
 * 按压动画语言（移植自 Mu3ic ui/Press.kt 的 bounceClick / pressHighlight）：
 * 按下 = 快速无过冲下压，松手 = MediumBouncy 回弹；缩放与高亮都经 graphicsLayer / draw lambda 后置读状态，
 * 动画帧零 recomposition。动画值必须在 layer / draw lambda 里读，见 Mu3ic docs/lessons/android-compose-animation.md。
 */

/** 按下：~90ms 快速下压，无过冲。 */
val PressDownSpec = tween<Float>(durationMillis = 90, easing = LinearOutSlowInEasing)

/** 松手：MediumBouncy 过冲回弹。 */
val PressUpSpec = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

/**
 * 小控件用：按压缩放 + clickable 一体（无 ripple，纯缩放反馈）。缩放层在 clickable 之前，
 * 调用点要把它放在 `clip` / `background` **之前**（`size → bounceClick → clip → background`），底色才进缩放层。
 */
fun Modifier.bounceClick(
    onClickLabel: String? = null,
    enabled: Boolean = true,
    role: Role? = null,
    pressedScale: Float = 0.94f,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(isPressed) {
        scale.animateTo(if (isPressed) pressedScale else 1f, if (isPressed) PressDownSpec else PressUpSpec)
    }
    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
}

/**
 * 给自带 interactionSource 的位点补按压缩放（底栏 tab 等，移植自 Mu3ic 的 pressScale）。
 * 用法：`val iss = remember { MutableInteractionSource() }` → 同时传给 clickable 与本 modifier。
 */
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.88f): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(isPressed) {
        scale.animateTo(if (isPressed) pressedScale else 1f, if (isPressed) PressDownSpec else PressUpSpec)
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * 全宽列表行用：不缩放，按压时叠一层渐入背景高亮，保留 ripple。
 * [highlight] 缺省 onSurface 8% alpha；[behind] = true 时高亮画在内容之下（不透明底色要用它，否则盖住图标）。
 * 给了 [onLongClick] 时长按到点先触感反馈再回调，读屏报成名为 [onLongClickLabel] 的操作。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressHighlight(
    onClickLabel: String? = null,
    enabled: Boolean = true,
    role: Role? = null,
    highlight: Color? = null,
    behind: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(isPressed) {
        alpha.animateTo(if (isPressed) 1f else 0f, if (isPressed) PressDownSpec else PressUpSpec)
    }
    val base = highlight ?: MaterialTheme.colorScheme.onSurface
    val targetAlpha = if (highlight != null) highlight.alpha else 0.08f
    this
        .drawWithContent {
            val a = alpha.value
            if (behind && a > 0.01f) drawRect(color = base, alpha = targetAlpha * a)
            drawContent()
            if (!behind && a > 0.01f) drawRect(color = base, alpha = targetAlpha * a)
        }
        .then(
            if (onLongClick == null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    enabled = enabled,
                    onClickLabel = onClickLabel,
                    role = role,
                    onClick = onClick,
                )
            } else {
                Modifier.combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    enabled = enabled,
                    onClickLabel = onClickLabel,
                    role = role,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                    onClick = onClick,
                )
            },
        )
}

/**
 * 只有长按动作的行：单击什么也不做，所以不带 click 语义（没有涟漪，读屏也不会报「双击激活」），
 * 只给一个名为 [onLongClickLabel] 的长按操作。长按到点先触感反馈再回调。
 */
fun Modifier.longPressOnly(onLongClickLabel: String?, onLongClick: () -> Unit): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val action = rememberUpdatedState(onLongClick)
    this
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                action.value()
            })
        }
        .semantics {
            onLongClick(label = onLongClickLabel) {
                action.value()
                true
            }
        }
}
