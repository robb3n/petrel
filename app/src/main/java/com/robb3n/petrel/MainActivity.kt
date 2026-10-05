package com.robb3n.petrel

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.robb3n.petrel.ui.PetrelApp
import com.robb3n.petrel.ui.model.Tab
import com.robb3n.petrel.ui.skin.PetrelSkin
import com.robb3n.petrel.ui.skin.rememberTabPager
import com.robb3n.petrel.ui.skin.skinOf

/** 主界面入口：VPN 授权、EXTRA_START、通知权限；界面本身在 ui/ 下。 */
class MainActivity : ComponentActivity() {
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) PetrelVpnService.start(this)
        else CoreBridge.fail("VPN 授权被拒绝")
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 选择器取消时 uri 为 null，仓库里什么也不做
    private val pickConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        ConfigRepository.importConfig(this, uri)
    }
    private val pickGeoIp = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        ConfigRepository.replaceGeoIp(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动不闪：setContent 之前先按当前皮肤与明暗设好窗口底色和系统栏图标色（UiPrefs 在第一次访问时同步读回）
        applyChrome(resolveDark(UiPrefs.tone.value, systemDark()), skinOf(UiPrefs.skin.value))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 磁贴冷启动后任务的根是 TileLaunchActivity；从通知等入口叠上来的主界面按返回时，结束自己会让 TileLaunchActivity
        // 回到前台、又把主界面拉出来，人退不出去。这时把整个任务退到后台（卡片留着，可以上锁）。
        // 这个回调最先注册、优先级最低：界面里的导航返回栈先处理，没得退了才轮到它。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isTaskRoot) {
                    moveTaskToBack(true)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        // Activity 带 savedInstanceState 重建（进程被杀后点最近任务卡片、后台时切了明暗）时，系统会用原 intent 重放，
        // 而且不带 LAUNCHED_FROM_HISTORY：这时不能再处理 EXTRA_START，否则悄悄打开了人刚关掉的 VPN
        if (savedInstanceState == null) handleStartExtra(intent)
        setContent {
            val skinKey by UiPrefs.skin.collectAsStateWithLifecycle()
            val tone by UiPrefs.tone.collectAsStateWithLifecycle()
            // 先解析出应用实际的明暗，再让系统栏图标色跟它走；这个值必须在组合期读，SideEffect 才会随它重跑
            val dark = resolveDark(tone, isSystemInDarkTheme())
            val skin = skinOf(skinKey)
            SideEffect { applyChrome(dark, skin) }
            // 导航状态放在皮肤之上：换皮肤时 Theme / Shell 换成别的 composable，里面建的 NavController / pager 状态会丢，人就回到首页
            val nav = rememberNavController()
            val pager = rememberTabPager(rememberPagerState { Tab.entries.size })
            skin.Theme(dark) {
                PetrelApp(
                    skin = skin,
                    nav = nav,
                    pager = pager,
                    // 很多文件管理器把 YAML 报成 application/octet-stream，所以不按 MIME 过滤
                    onImportConfig = { pickConfig.launch(arrayOf("*/*")) },
                    onReplaceGeoIp = { pickGeoIp.launch(arrayOf("*/*")) },
                    onToggle = ::toggle,
                    onOpenLogin = ::openLogin,
                    onCopyLogin = { copyLoginUrl(this) },
                )
            }
        }
    }

    private fun systemDark() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * 窗口底色 = 该皮肤该明暗下的页面底色；状态栏 / 导航栏图标色按应用实际明暗选
     * （`SystemBarStyle.light` = 浅色背景配深色图标，`.dark` = 白图标），不直接照抄系统深色开关。
     */
    private fun applyChrome(dark: Boolean, skin: PetrelSkin) {
        window.setBackgroundDrawable(ColorDrawable(skin.pageColor(dark)))
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }

    override fun onResume() {
        super.onResume()
        // 配置可能在界面打开期间被推进来（scripts/push-config.sh），回到前台时重新检查
        ConfigRepository.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartExtra(intent)
    }

    /** 磁贴在未授权时会带 EXTRA_START 拉起这里，授权后直接开。 */
    private fun handleStartExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START, false) == true && !CoreBridge.state.value.active) {
            intent.removeExtra(EXTRA_START)
            toggle()
        }
    }

    private fun toggle() {
        if (CoreBridge.state.value.active) {
            PetrelVpnService.stop(this)
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else PetrelVpnService.start(this)
    }

    /** URL 可能刚被清空（已登录）；手机上也可能没有浏览器。 */
    private fun openLogin() {
        val url = CoreBridge.state.value.loginURL
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有能打开链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_START = "com.robb3n.petrel.EXTRA_START"
    }
}
