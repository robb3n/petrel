package com.robb3n.petrel.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 画稿图标：24×24 viewBox 的线条图标，stroke 1.8、圆头圆角（画稿 `.ic`）。
 * 路径字符串逐字取自 docs/spec/v1.md 的图标表；`<circle>` / `<rect>` 在 Compose 里没有对应图元，
 * 换成等价的圆弧路径，形状不变。线条色是占位，使用处用 tint 染色。
 */
object PetrelIcons {
    /** 连接（底部导航） */
    val Power by lazy { icon("Power") { path("M12 3v8"); path("M6.3 6.3a8 8 0 1 0 11.4 0") } }

    /** 夜航电源键里的电源图标：同一条路径，线宽 2（画稿 `.pwr .ic{stroke-width:2}`）。 */
    val PowerHeavy by lazy { icon("PowerHeavy", strokeWidth = 2f) { path("M12 3v8"); path("M6.3 6.3a8 8 0 1 0 11.4 0") } }

    /**
     * 设置齿轮（v1 没有，同规格补的）：24 网格、stroke 1.8、圆头圆角、无填充。
     * 8 齿外轮廓（外半径 9.6、齿根半径 7.4）加中心圆 r3。
     */
    val Settings by lazy {
        icon("Settings") {
            path(
                "M10.46 4.76L10.83 2.47L13.17 2.47L13.54 4.76L16.03 5.79L17.91 4.44L19.56 6.09L18.21 7.97L19.24 10.46L21.53 10.83" +
                    "L21.53 13.17L19.24 13.54L18.21 16.03L19.56 17.91L17.91 19.56L16.03 18.21L13.54 19.24L13.17 21.53L10.83 21.53" +
                    "L10.46 19.24L7.97 18.21L6.09 19.56L4.44 17.91L5.79 16.03L4.76 13.54L2.47 13.17L2.47 10.83L4.76 10.46L5.79 7.97" +
                    "L4.44 6.09L6.09 4.44L7.97 5.79Z",
            )
            circle(12f, 12f, 3f)
        }
    }

    /** 节点（底部导航） */
    val Nodes by lazy {
        icon("Nodes") {
            circle(5.5f, 6f, 1.8f); circle(5.5f, 12f, 1.8f); circle(5.5f, 18f, 1.8f)
            path("M10 6h10M10 12h10M10 18h10")
        }
    }

    /** tailnet（底部导航、首页行） */
    val Tailnet by lazy {
        icon("Tailnet") {
            circle(12f, 5f, 2.2f); circle(5f, 18f, 2.2f); circle(19f, 18f, 2.2f)
            path("M11 7 6 16M13 7l5 9M7.2 18h9.6")
        }
    }

    /** 配置文件 */
    val ConfigFile by lazy {
        icon("ConfigFile") {
            path("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"); path("M14 3v5h5")
        }
    }

    /** 行尾箭头 */
    val Chevron by lazy { icon("Chevron") { path("m9 6 6 6-6 6") } }

    /** 返回 */
    val Back by lazy { icon("Back") { path("M19 12H5M11 6l-6 6 6 6") } }

    /** 复制 */
    val Copy by lazy {
        icon("Copy") { rect(9f, 9f, 11f, 11f, 2f); path("M5 15V5a2 2 0 0 1 2-2h10") }
    }

    /** 测延迟 */
    /** 刷新（首页顶栏）：一段 3/4 圆弧，末端接右上角的箭头。 */
    val Refresh by lazy {
        icon("Refresh") { path("M20 12a8 8 0 1 1-2.34-5.66L20 8.5"); path("M20 4v4.5h-4.5") }
    }

    val Timer by lazy {
        icon("Timer") { circle(12f, 13f, 8f); path("M12 9v4l2.5 2.5M9 2.5h6") }
    }

    /** 登出 */
    val Logout by lazy {
        icon("Logout") { path("M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3"); path("M10 17l-5-5 5-5M5 12h11") }
    }

    /** 导入 */
    val Import by lazy { icon("Import") { path("M12 4v11M7 10l5 5 5-5"); path("M5 20h14") } }

    /** 警示 */
    val Alert by lazy { icon("Alert") { circle(12f, 12f, 9f); path("M12 7.5v5.5M12 16.5v.01") } }

    /** 通过 */
    val Check by lazy { icon("Check") { path("m5 12.5 4.5 4.5L19 7.5") } }

    /** 浏览器打开 */
    val OpenBrowser by lazy {
        icon("OpenBrowser") {
            path("M14 4h6v6M20 4l-9 9")
            path("M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
        }
    }
}

private class IconScope(val builder: ImageVector.Builder, val strokeWidth: Float) {
    private fun stroke(nodes: List<androidx.compose.ui.graphics.vector.PathNode>) {
        builder.addPath(
            pathData = nodes,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun path(d: String) = stroke(addPathNodes(d))

    fun circle(cx: Float, cy: Float, r: Float) = stroke(
        PathBuilder().apply {
            moveTo(cx - r, cy)
            arcToRelative(r, r, 0f, true, false, 2 * r, 0f)
            arcToRelative(r, r, 0f, true, false, -2 * r, 0f)
            close()
        }.nodes,
    )

    fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) = stroke(
        PathBuilder().apply {
            moveTo(x + rx, y)
            horizontalLineToRelative(w - 2 * rx)
            arcToRelative(rx, rx, 0f, false, true, rx, rx)
            verticalLineToRelative(h - 2 * rx)
            arcToRelative(rx, rx, 0f, false, true, -rx, rx)
            horizontalLineToRelative(-(w - 2 * rx))
            arcToRelative(rx, rx, 0f, false, true, -rx, -rx)
            verticalLineToRelative(-(h - 2 * rx))
            arcToRelative(rx, rx, 0f, false, true, rx, -rx)
            close()
        }.nodes,
    )
}

private fun icon(name: String, strokeWidth: Float = 1.8f, block: IconScope.() -> Unit): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    IconScope(b, strokeWidth).block()
    return b.build()
}
