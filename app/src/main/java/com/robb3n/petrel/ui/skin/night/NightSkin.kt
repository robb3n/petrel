package com.robb3n.petrel.ui.skin.night

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.robb3n.petrel.ui.model.ConfigUi
import com.robb3n.petrel.ui.model.HomeUi
import com.robb3n.petrel.ui.model.NodesUi
import com.robb3n.petrel.ui.model.PetrelActions
import com.robb3n.petrel.ui.model.SettingsUi
import com.robb3n.petrel.ui.model.TailnetUi
import com.robb3n.petrel.ui.skin.PetrelSkin
import com.robb3n.petrel.ui.skin.TabPager

/** 夜航：深色仪表盘，Barlow Condensed + IBM Plex，状态灯与航线图。视觉真相源是 docs/spec/assets/ui-skins/src/Night{Home,Nodes,Tailnet}.dc.html。 */
object NightSkin : PetrelSkin {
    override fun pageColor(dark: Boolean): Int = (if (dark) NightDark else NightLight).bg.toArgb()

    @Composable
    override fun Theme(dark: Boolean, content: @Composable () -> Unit) = NightTheme(dark, content)

    /** 底栏贴底（`.dock`），内容区止于底栏上沿；二级页没有底栏。 */
    @Composable
    override fun Shell(tabs: TabPager?, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Night.colors.bg)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            if (tabs != null) NightDock(tabs)
        }
    }

    @Composable override fun Home(ui: HomeUi, a: PetrelActions) = NightHome(ui, a)
    @Composable override fun Nodes(ui: NodesUi, a: PetrelActions) = NightNodes(ui, a)
    @Composable override fun Tailnet(ui: TailnetUi, a: PetrelActions) = NightTailnet(ui, a)
    @Composable override fun Config(ui: ConfigUi, a: PetrelActions) = NightConfig(ui, a)
    @Composable override fun Settings(ui: SettingsUi, a: PetrelActions) = NightSettings(ui, a)
}
