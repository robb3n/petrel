package com.robb3n.petrel.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.robb3n.petrel.BuildConfig
import com.robb3n.petrel.ConfigRepository
import com.robb3n.petrel.CoreBridge
import com.robb3n.petrel.GroupsRepository
import com.robb3n.petrel.TailnetRepository
import com.robb3n.petrel.UiPrefs
import com.robb3n.petrel.copyToClipboard
import com.robb3n.petrel.ui.model.PetrelActions
import com.robb3n.petrel.ui.model.Tab
import com.robb3n.petrel.ui.model.buildConfig
import com.robb3n.petrel.ui.model.buildHome
import com.robb3n.petrel.ui.model.buildNodes
import com.robb3n.petrel.ui.model.buildSettings
import com.robb3n.petrel.ui.model.buildTailnet
import com.robb3n.petrel.ui.skin.IMPLEMENTED_SKINS
import com.robb3n.petrel.ui.skin.PetrelSkin
import com.robb3n.petrel.ui.skin.TabPager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object Routes {
    /** 三个一级 tab 所在的横向 pager（[TabPager]）。 */
    const val TABS = "tabs"
    const val CONFIG = "config"
    const val SETTINGS = "settings"
}

private const val POLL_MILLIS = 5_000L
private const val PROBE_MILLIS = 30_000L

/**
 * 导航骨架与数据汇集：一级标签（连接 / 节点 / tailnet）是 `tabs` 路由里同一个横向 pager 的三页（[TabPager]，同 Mu3ic 的主壳），
 * `config`、`settings` 是推在它上面的二级页（无底栏）。
 * 这里把 CoreBridge 与各 repository 的数据推导成界面模型（`ui/model`）、拼出动作，交给当前皮肤去画；
 * 换皮肤只是重组，人停在原来的页面不动：NavController 与 [pager] 由调用方（MainActivity 的 setContent 根部）持有，
 * 不能建在这里——各皮肤的 Theme 是不同的 composable，PetrelApp 会随它整棵重建，里面建的 NavController 会回到首页。
 */
@Composable
fun PetrelApp(
    skin: PetrelSkin,
    nav: NavHostController,
    pager: TabPager,
    onImportConfig: () -> Unit,
    onReplaceGeoIp: () -> Unit,
    onToggle: () -> Unit,
    onOpenLogin: () -> Unit,
    onCopyLogin: () -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val s by CoreBridge.state.collectAsStateWithLifecycle()
    val config by ConfigRepository.info.collectAsStateWithLifecycle()
    val import by ConfigRepository.import.collectAsStateWithLifecycle()
    val geo by ConfigRepository.geo.collectAsStateWithLifecycle()
    val geoBusy by ConfigRepository.geoBusy.collectAsStateWithLifecycle()
    val groups by GroupsRepository.groups.collectAsStateWithLifecycle()
    val testing by GroupsRepository.testing.collectAsStateWithLifecycle()
    val tailnet by TailnetRepository.status.collectAsStateWithLifecycle()
    val skinKey by UiPrefs.skin.collectAsStateWithLifecycle()
    val tone by UiPrefs.tone.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // 首帧返回栈还没建好时 route 是 null，当作在 tabs 上
    val onTabs = route == null || route == Routes.TABS

    // 切 tab：在二级页上先退回 tabs，再让 pager 滑过去（底栏指示器跟着 pager 走）
    fun goTab(t: Tab) {
        val r = nav.currentDestination?.route
        if (r != null && r != Routes.TABS) nav.popBackStack(Routes.TABS, inclusive = false)
        pager.select(t)
    }

    fun push(route: String) = nav.navigate(route) { launchSingleTop = true }

    // 导入失败的横幅一直显示，直到下一次导入开始或离开配置页
    DisposableEffect(nav) {
        val l = NavController.OnDestinationChangedListener { _, d, _ ->
            if (d.route != Routes.CONFIG) ConfigRepository.clearError()
        }
        nav.addOnDestinationChangedListener(l)
        onDispose { nav.removeOnDestinationChangedListener(l) }
    }

    // 从首页（首次使用的按钮）发起的导入失败时，横幅在配置页：带过去
    LaunchedEffect(import.error) {
        if (import.error != null && nav.currentDestination?.route != Routes.CONFIG) push(Routes.CONFIG)
    }

    // 进入配置页时在 IO 线程算 GeoIP 库状态。离开页面时收起横幅由上面的导航监听负责：
    // 不能放在页面的 onDispose 里，明暗切换、旋转屏幕重建 Activity 时它也会触发，横幅就丢了
    LaunchedEffect(route == Routes.CONFIG) {
        if (route == Routes.CONFIG) ConfigRepository.refreshGeo(ctx)
    }

    // 节点数据：连接页或 tailnet 标签可见（STARTED）且 tailnet 在跑时每 5 秒拉一次；tailnet 状态一变就立刻拉
    val polling = s.tailnetLive && onTabs && (pager.current == Tab.Home || pager.current == Tab.Tailnet)
    LaunchedEffect(polling, s.tailnet) {
        if (!polling) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                TailnetRepository.refresh()
                delay(POLL_MILLIS)
            }
        }
    }

    // 当前出站的延迟：界面可见（STARTED）且 VPN 在跑时每 30 秒探一次当前选中的节点（同 MihomoBar 的链路体检节奏）。
    // 只在前台做，后台不定时唤醒；刚测过的 Go 侧会跳过，回到前台立即调一次也不浪费
    LaunchedEffect(s.running) {
        if (!s.running) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                GroupsRepository.probeSelected()
                delay(PROBE_MILLIS)
            }
        }
    }

    // 非连接页的 tab 上按返回 = 回连接页；连接页再按返回才退出（同 Mu3ic）
    BackHandler(enabled = onTabs && pager.current != Tab.Home) { pager.select(Tab.Home) }

    val actions = remember(nav, pager) {
        PetrelActions(
            toggle = onToggle,
            importConfig = onImportConfig,
            replaceGeoIp = onReplaceGeoIp,
            select = { group, node ->
                GroupsRepository.select(group, node) { msg ->
                    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, "切换失败：$msg", Toast.LENGTH_SHORT).show() }
                }
            },
            testDelay = GroupsRepository::testDelay,
            refresh = GroupsRepository::refresh,
            logout = {
                scope.launch {
                    runCatching { TailnetRepository.logout() }.onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "登出失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            openLogin = onOpenLogin,
            copyLogin = onCopyLogin,
            copyAddress = { ip -> copyToClipboard(ctx, "tailnet address", ip, "已复制地址") },
            setSkin = UiPrefs::setSkin,
            setTone = UiPrefs::setTone,
            openNodes = { goTab(Tab.Nodes) },
            openTailnet = { goTab(Tab.Tailnet) },
            openConfig = { push(Routes.CONFIG) },
            openSettings = { push(Routes.SETTINGS) },
            back = { nav.popBackStack() },
            openTab = ::goTab,
        )
    }

    skin.Shell(tabs = if (onTabs) pager else null) {
        NavHost(nav, startDestination = Routes.TABS) {
            composable(Routes.TABS) {
                // 三页都留在组合里（页数少），切走再切回滚动位置还在
                HorizontalPager(pager.state, Modifier.fillMaxSize(), beyondViewportPageCount = pager.tabs.size - 1) { page ->
                    when (pager.tabs[page]) {
                        Tab.Home -> skin.Home(
                            remember(s, config, import, groups, tailnet, testing) { buildHome(s, config, import, groups, tailnet, testing) },
                            actions,
                        )
                        Tab.Nodes -> skin.Nodes(remember(s, groups, testing) { buildNodes(s, groups, testing) }, actions)
                        Tab.Tailnet -> skin.Tailnet(remember(s, tailnet) { buildTailnet(s, tailnet) }, actions)
                    }
                }
            }
            composable(Routes.CONFIG) {
                skin.Config(remember(config, import, geo, geoBusy) { buildConfig(config, import, geo, geoBusy) }, actions)
            }
            composable(Routes.SETTINGS) {
                skin.Settings(
                    remember(skinKey, tone, config) {
                        buildSettings(skinKey, IMPLEMENTED_SKINS, tone, BuildConfig.VERSION_NAME, config)
                    },
                    actions,
                )
            }
        }
    }
}
