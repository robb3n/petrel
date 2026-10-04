package com.robb3n.petrel.ui.skin.shoal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onPlaced
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
import com.robb3n.petrel.ui.pressScale
import com.robb3n.petrel.ui.skin.CssText
import com.robb3n.petrel.ui.skin.PetrelSkin
import com.robb3n.petrel.ui.skin.TabPager
import com.robb3n.petrel.ui.skin.tabCloseness
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt

/** Shoal：奶油浮岛 + 橄榄棕，默认皮肤。视觉真相源是 docs/spec/assets/ui-skins/src/{Main,ShoalNodes,ShoalTailnet}.dc.html。 */
object ShoalSkin : PetrelSkin {
    override fun pageColor(dark: Boolean): Int = (if (dark) ShoalDark else ShoalLight).wall.toArgb()

    @Composable
    override fun Theme(dark: Boolean, content: @Composable () -> Unit) = ShoalTheme(dark, content)

    @Composable
    override fun Shell(tabs: TabPager?, content: @Composable () -> Unit) {
        val c = Shoal.colors
        Box(Modifier.fillMaxSize().background(c.wall)) {
            content()
            if (tabs != null) FloatingNav(tabs, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Composable override fun Home(ui: HomeUi, a: PetrelActions) = ShoalHome(ui, a)
    @Composable override fun Nodes(ui: NodesUi, a: PetrelActions) = ShoalNodes(ui, a)
    @Composable override fun Tailnet(ui: TailnetUi, a: PetrelActions) = ShoalTailnet(ui, a)
    @Composable override fun Config(ui: ConfigUi, a: PetrelActions) = ShoalConfig(ui, a)
    @Composable override fun Settings(ui: SettingsUi, a: PetrelActions) = ShoalSettings(ui, a)
}

private class NavItem(val tab: Tab, val label: String, val icon: @Composable (Boolean) -> androidx.compose.ui.graphics.vector.ImageVector)

private val NAV_ITEMS = listOf(
    NavItem(Tab.Home, "连接") { on -> if (on) Ms.powerSettingsNewFill else Ms.powerSettingsNew },
    NavItem(Tab.Nodes, "节点") { on -> if (on) Ms.hubFill else Ms.hub },
    NavItem(Tab.Tailnet, "tailnet") { on -> if (on) Ms.lanFill else Ms.lan },
)

/**
 * `.fnav`：悬浮底栏（左右 12、底 16 + 手势条 inset、高 56、`--sf` 底、胶囊、`--shIsle`、三列等宽、间距 4、内边距 5）；项内图标 21、文字 13/500、间距 6。
 *
 * 动效同 Mu3ic 的 `FloatingNav`：选中底是一整块 `--ol` 滑动胶囊，位置**就是 pager 的实时位置**（[TabPager.position]）——
 * 根页上横滑翻页、点某项（pager 滑过去）、按住胶囊横拖，动的都是 pager，页面与胶囊永远同步。拖胶囊时手指每横移一项的间距，
 * pager 就翻一页；松手按落点与甩动方向吸到一页。各项字色 / 图标按离胶囊多近在 `--tx2` 与 `--onol` 之间渐变，胶囊过半时图标换 FILL 1；
 * 按下缩到 0.95 并带涟漪。
 */
@Composable
private fun FloatingNav(tabs: TabPager, modifier: Modifier = Modifier) {
    val c = Shoal.colors
    val track = remember { PillTrack(NAV_ITEMS.size) }
    val pos = tabs.position
    Row(
        modifier
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
            .fillMaxWidth()
            .height(56.dp)
            .isleSurface(c, PillShape)
            .padding(5.dp)
            .drawBehind { drawPill(track, tabs.position, c.ol) }
            .pointerInput(track, tabs) {
                val last = (NAV_ITEMS.size - 1).toFloat()
                val velocity = VelocityTracker()
                var startPos = 0f
                var dragPx = 0f
                var target = 0f
                // 按相对位移拖（起拖处的胶囊位置 + 手指横移 / 项间距）：从哪儿按下都不会先跳一下
                detectHorizontalDragGestures(
                    onDragStart = {
                        velocity.resetTracking()
                        startPos = tabs.position
                        dragPx = 0f
                        target = startPos
                    },
                    onDragEnd = {
                        val itemsPerSec = velocity.calculateVelocity().x / track.itemSpan()
                        val end = when {
                            itemsPerSec > PILL_FLING_ITEMS_PER_SEC -> ceil(target)
                            itemsPerSec < -PILL_FLING_ITEMS_PER_SEC -> floor(target)
                            else -> round(target)
                        }
                        tabs.settle(end.toInt())
                    },
                    onDragCancel = { tabs.settle(target.roundToInt()) },
                ) { change, dx ->
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    dragPx += dx
                    target = (startPos + dragPx / track.itemSpan()).coerceIn(0f, last)
                    tabs.scrollTo(target)
                }
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NAV_ITEMS.forEachIndexed { i, item ->
            FloatingNavTab(
                item = item,
                closeness = tabCloseness(pos, i),
                selected = tabs.current == item.tab,
                modifier = Modifier.weight(1f).onPlaced { track.place(i, it) },
            ) { tabs.select(item.tab) }
        }
    }
}

/** 松手时横甩超过这个速度（项 / 秒）就顺着甩的方向翻到下一页，否则吸到最近的一页。 */
private const val PILL_FLING_ITEMS_PER_SEC = 1.2f

@Composable
private fun FloatingNavTab(item: NavItem, closeness: Float, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Shoal.colors
    val fg = lerp(c.tx2, c.onol, closeness)
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxHeight()
            .pressScale(press, 0.95f)
            .clip(PillShape)
            .clickable(interactionSource = press, indication = ripple(), role = Role.Tab, onClickLabel = item.label, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon(closeness > 0.5f), null, tint = fg, modifier = Modifier.size(21.dp))
        CssText(item.label, ui(13f, FontWeight.Medium, 17.55f), fg, softWrap = false)
    }
}
