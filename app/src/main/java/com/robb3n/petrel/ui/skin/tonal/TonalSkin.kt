package com.robb3n.petrel.ui.skin.tonal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.robb3n.petrel.ui.model.ConfigUi
import com.robb3n.petrel.ui.model.HomeUi
import com.robb3n.petrel.ui.model.NodesUi
import com.robb3n.petrel.ui.model.PetrelActions
import com.robb3n.petrel.ui.model.SettingsUi
import com.robb3n.petrel.ui.model.Tab
import com.robb3n.petrel.ui.model.TailnetUi
import com.robb3n.petrel.ui.skin.CssText
import com.robb3n.petrel.ui.skin.PetrelSkin
import com.robb3n.petrel.ui.skin.TabPager
import com.robb3n.petrel.ui.skin.tabCloseness

/** Tonal：Material 3 Expressive，固定蓝色调色板、系统字体、饼干形电源键。视觉真相源是 docs/spec/assets/ui-skins/src/Tonal{Home,Nodes,Tailnet}.dc.html。 */
object TonalSkin : PetrelSkin {
    override fun pageColor(dark: Boolean): Int = (if (dark) TonalDark else TonalLight).sf.toArgb()

    @Composable
    override fun Theme(dark: Boolean, content: @Composable () -> Unit) = TonalTheme(dark, content)

    /** M3 导航栏（`.nav`）贴底，内容区止于它的上沿；二级页没有导航栏。 */
    @Composable
    override fun Shell(tabs: TabPager?, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Tonal.colors.sf)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            if (tabs != null) NavBar(tabs)
        }
    }

    @Composable override fun Home(ui: HomeUi, a: PetrelActions) = TonalHome(ui, a)
    @Composable override fun Nodes(ui: NodesUi, a: PetrelActions) = TonalNodes(ui, a)
    @Composable override fun Tailnet(ui: TailnetUi, a: PetrelActions) = TonalTailnet(ui, a)
    @Composable override fun Config(ui: ConfigUi, a: PetrelActions) = TonalConfig(ui, a)
    @Composable override fun Settings(ui: SettingsUi, a: PetrelActions) = TonalSettings(ui, a)
}

private class NavItem(val tab: Tab, val label: String, val icon: ImageVector, val iconOn: ImageVector)

@Composable
private fun navItems() = listOf(
    NavItem(Tab.Home, "连接", Ms4.powerSettingsNew, Ms4.powerSettingsNewFill),
    NavItem(Tab.Nodes, "节点", Ms4.route, Ms4.routeFill),
    NavItem(Tab.Tailnet, "tailnet", Ms4.hub, Ms4.hubFill),
)

/**
 * `.nav`：高 80 + 手势条 inset、`--sfC` 底、三列等宽、`padding:12 0 16`。每项竖排：指示胶囊 56 × 32（圆角 16，照画稿，不用 M3 默认的 64 × 32）
 * + 标签 12/16 500、间距 4；选中项胶囊 `--secC`、图标转 FILL 1 `--onSecC`，标签 `--on` 700。
 * 指示胶囊只有一块，位置就是 pager 的实时位置（[TabPager.position]），翻页时在各项之间滑；图标、标签颜色按离胶囊多近渐变，过半换实心图标与粗体。
 */
@Composable
private fun NavBar(tabs: TabPager) {
    val c = Tonal.colors
    val items = navItems()
    val pos = tabs.position
    Box(Modifier.fillMaxWidth().background(c.sfC).navigationBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(top = 12.dp, bottom = 16.dp)
                .drawBehind {
                    val itemW = size.width / items.size
                    val w = 56.dp.toPx()
                    val left = (tabs.position + 0.5f) * itemW - w / 2f
                    drawRoundRect(c.secC, Offset(left, 0f), Size(w, 32.dp.toPx()), CornerRadius(16.dp.toPx()))
                },
        ) {
            items.forEachIndexed { i, item ->
                val near = tabCloseness(pos, i)
                val on = near > 0.5f
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Tab, onClickLabel = item.label) { tabs.select(item.tab) }
                        .semantics { selected = tabs.current == item.tab },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(width = 56.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                        Icon(if (on) item.iconOn else item.icon, null, tint = lerp(c.onV, c.onSecC, near), modifier = Modifier.size(24.dp))
                    }
                    CssText(
                        item.label, tui(12f, if (on) FontWeight.Bold else FontWeight.Medium, 16f),
                        lerp(c.onV, c.on, near), softWrap = false,
                    )
                }
            }
        }
    }
}
