package com.robb3n.petrel.ui.skin

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.robb3n.petrel.ui.model.Tab
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 一级 tab 的横向翻页（同 Mu3ic 的主壳）：三个 tab 是同一个 pager 的三页，根页上左右滑动（跟手）或点底栏切换。
 * 底栏的选中指示（Shoal 的胶囊、夜航的顶线、Tonal 的选中底）位置**就是 pager 的实时位置**（[position]），
 * 指示器只负责画：滑页、点某项、拖胶囊，动的都是 pager，页面与指示器永远同步。
 *
 * 状态由 MainActivity 持有（与 NavController 同层），换皮肤时停在原来那页。
 */
@Stable
class TabPager internal constructor(val state: PagerState, private val scope: CoroutineScope) {
    val tabs: List<Tab> = Tab.entries

    /** 实时位置：页序号 + 页内偏移，翻页过程中带小数。 */
    val position: Float get() = state.currentPage + state.currentPageOffsetFraction

    /** 当前页（过半即换）。 */
    val current: Tab get() = tabs[state.currentPage.coerceIn(0, tabs.lastIndex)]

    /** 滑到 [tab] 那页。 */
    fun select(tab: Tab) {
        scope.launch { state.animateScrollToPage(tab.ordinal) }
    }

    /** 拖指示器时把 pager 滚到 [target]（页序号，可带小数）。偏移在 launch 之前算好：scrollToPage 只收 ±0.5 以内的页内偏移。 */
    fun scrollTo(target: Float) {
        val t = target.coerceIn(0f, tabs.lastIndex.toFloat())
        val page = t.roundToInt()
        val off = t - page
        scope.launch { state.scrollToPage(page, off) }
    }

    /** 松手：从当前位置接着滑到第 [page] 页。 */
    fun settle(page: Int) {
        scope.launch { state.animateScrollToPage(page.coerceIn(0, tabs.lastIndex)) }
    }
}

@Composable
fun rememberTabPager(state: PagerState): TabPager {
    val scope = rememberCoroutineScope()
    return remember(state, scope) { TabPager(state, scope) }
}

/** 第 [index] 项离指示器有多近（1 = 指示器正在它上面，0 = 隔一项以上）：字色 / 图标据此在未选与选中之间过渡。 */
fun tabCloseness(position: Float, index: Int): Float = (1f - abs(position - index)).coerceIn(0f, 1f)
