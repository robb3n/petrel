package com.robb3n.petrel.ui.skin

import androidx.compose.runtime.Composable
import com.robb3n.petrel.Skin
import com.robb3n.petrel.ui.model.ConfigUi
import com.robb3n.petrel.ui.model.HomeUi
import com.robb3n.petrel.ui.model.NodesUi
import com.robb3n.petrel.ui.model.PetrelActions
import com.robb3n.petrel.ui.model.SettingsUi
import com.robb3n.petrel.ui.model.TailnetUi
import com.robb3n.petrel.ui.skin.night.NightSkin
import com.robb3n.petrel.ui.skin.shoal.ShoalSkin
import com.robb3n.petrel.ui.skin.tonal.TonalSkin

/**
 * 一套皮肤：同一份界面模型（`ui/model`）、同一组动作，各画各的。
 * 皮肤包里不得直接读 repository 或 CoreBridge，只渲染模型、调用 [PetrelActions]（docs/spec/skins.md §2.2）。
 */
interface PetrelSkin {
    /** 窗口底色（ARGB）：`setContent` 之前按它设 window background，避免启动时闪别的颜色。 */
    fun pageColor(dark: Boolean): Int

    /** token CompositionLocal + MaterialTheme colorScheme 映射。 */
    @Composable fun Theme(dark: Boolean, content: @Composable () -> Unit)

    /** 底栏与 inset；[tabs] == null 是二级页（无底栏）。底栏的选中指示跟着 [TabPager.position] 走，点某项调 [TabPager.select]。 */
    @Composable fun Shell(tabs: TabPager?, content: @Composable () -> Unit)

    @Composable fun Home(ui: HomeUi, a: PetrelActions)
    @Composable fun Nodes(ui: NodesUi, a: PetrelActions)
    @Composable fun Tailnet(ui: TailnetUi, a: PetrelActions)
    @Composable fun Config(ui: ConfigUi, a: PetrelActions)
    @Composable fun Settings(ui: SettingsUi, a: PetrelActions)
}

/** 已经实现的皮肤，设置页只列这些；只有一套时「皮肤」整行不显示。 */
val IMPLEMENTED_SKINS: List<Skin> = listOf(Skin.Shoal, Skin.Night, Skin.Tonal)

/** 持久化值对应的皮肤实现。 */
fun skinOf(skin: Skin): PetrelSkin = when (skin) {
    Skin.Shoal -> ShoalSkin
    Skin.Night -> NightSkin
    Skin.Tonal -> TonalSkin
}
