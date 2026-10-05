package com.robb3n.petrel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import com.robb3n.petrel.core.ptcore.Ptcore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * 唯一的 VpnService：TUN fd 交给 Go 内核（mihomo），tailnet 由内核里的 tsnet 承载。
 * 本 App 自身被排除出 VPN（addDisallowedApplication），mihomo 出站与 tsnet 的套接字都走底层网络，不会自环。
 */
class PetrelVpnService : VpnService() {
    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var running = false

    /** onDestroy 之后置位：worker 里还排着的 startVpn / restartVpn 不再执行。 */
    @Volatile
    private var destroyed = false

    /** 最近一次 onStartCommand 的 startId（任何 action），只在主线程读写；onRevoke 等没有 intent 的停止请求用它。 */
    private var lastStartId = 0

    /** 最近一次「要服务继续活着」的请求（START / 系统拉起 / 进程被杀后重启）的 startId，只在主线程读写。 */
    private var latestStartRequestId = 0

    override fun onCreate() {
        super.onCreate()
        // 状态变化时刷新常驻通知
        scope.launch {
            // 只为进行中的状态发通知：stopped 状态没有对应的文案，晚到的一次 update 还会把刚撤掉的通知又发出来
            CoreBridge.state.collect { s -> if (s.active) Notifications.update(this@PetrelVpnService, s) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_COPY_LOGIN) {
            copyLoginUrl(this)
            // 通知可能是残留的：服务本来没在跑时，这次调用不该把它留下来
            if (!active()) stopSelf(startId)
            return stickyIfActive()
        }
        if (intent?.action == ACTION_STOP) {
            worker.execute { stopVpn(startId) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            worker.execute {
                // 与 startVpn / stopVpn 同在 worker 上排队，running 此刻是定数；没在跑就什么也不做，也不启动
                if (running) restartVpn(startId) else stopSelf(startId)
            }
            // 系统按最近一次 onStartCommand 的返回值决定进程被杀后要不要重启服务：运行中的辅助 action 也得是 STICKY
            return stickyIfActive()
        }
        // ACTION_START、系统「始终开启的 VPN」拉起（action = android.net.VpnService）、进程被杀后重启（intent = null）都走这里。
        latestStartRequestId = startId
        // 已经在运行时不改状态：startVpn 会直接返回，没人再 publish，「连接中」就卡住了
        if (!running) CoreBridge.markStarting()
        ServiceCompat.startForeground(
            this, Notifications.ID, Notifications.build(this, CoreBridge.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
        worker.execute { startVpn(startId) }
        return START_STICKY
    }

    private fun active() = running || CoreBridge.state.value.active

    private fun stickyIfActive() = if (active()) START_STICKY else START_NOT_STICKY


    /** [startId] 是排队它的那次启动请求的 id：失败要停服务时，只在期间没有更新的请求时才停。 */
    private fun startVpn(startId: Int) {
        if (destroyed) {
            Log.i(TAG, "service destroyed, queued start dropped")
            // 入口已经把状态改成「连接中」：按内核的真实状态补推，否则磁贴、通知、首页会一直卡着
            CoreBridge.onState(Ptcore.status())
            return
        }
        if (running) {
            Log.i(TAG, "vpn already running")
            CoreBridge.onState(Ptcore.status()) // 入口可能已经把状态改成「连接中」，这里按内核的真实状态补推一次
            return
        }
        val config = File(filesDir, ConfigRepository.CONFIG_FILE)
        if (!config.exists()) {
            failAndStop("未找到配置：${config.path}", startId)
            return
        }
        // 配置关了 IPv6 就不接管：VpnService 对没有地址和路由的地址族一律拦截（不泄漏），App 立刻得到不可达并退回 IPv4。
        // 收进 TUN 反而坏事：mihomo 拨不出 IPv6，App 看到的是连上又断，不会退回。见 docs/lessons/vpn-tun-stack.md
        val ipv6 = Ptcore.configIPv6(config.absolutePath)
        val pfd: ParcelFileDescriptor? = try {
            Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .apply { if (ipv6) addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0) }
                .addDnsServer("172.19.0.2")
                .setMtu(1400)
                .addDisallowedApplication(packageName)
                .setUnderlyingNetworks(null)
                .setConfigureIntent(
                    PendingIntent.getActivity(
                        this, RequestCodes.VPN_CONFIGURE, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .establish()
        } catch (e: Exception) {
            failAndStop("建立 VPN 失败：${e.message}", startId, "建立 VPN 失败：${e.javaClass.simpleName}")
            return
        }
        if (pfd == null) {
            failAndStop("VPN 未授权", startId)
            return
        }
        val fd = pfd.detachFd()
        try {
            Ptcore.start(filesDir.absolutePath, config.absolutePath, fd.toLong(), TailnetHostname.get(this), CoreBridge)
        } catch (e: Exception) {
            // fd 已交给 Go：失败时由 Go 侧关闭或交 mihomo 清理，这里不再碰它，免得重复关闭误伤被复用的编号。
            // Go 的错误形如「parse config: <mihomo 的解析错误>」，后半段可能带配置行与凭据：日志只记我们自己加的前缀
            val msg = e.message.orEmpty()
            failAndStop("启动内核失败：$msg", startId, "启动内核失败：${msg.substringBefore(':').ifEmpty { e.javaClass.simpleName }}")
            return
        }
        running = true
        try {
            registerNetworkCallback()
        } catch (e: Exception) {
            // 不包的话异常会从 worker 抛出、杀掉整个进程；没有网络回调切网后 tailnet 不会恢复，所以按启动失败处理
            stopCore()
            failAndStop("监听网络变化失败：${e.javaClass.simpleName}", startId)
            return
        }
        Log.i(TAG, "vpn started")
    }

    /** 停内核：注销网络回调、关 mihomo 与 tsnet。旧 TUN fd 由 Ptcore.stop() 经 mihomo 关闭，Kotlin 不碰。 */
    private fun stopCore() {
        unregisterNetworkCallback()
        Ptcore.stop()
        running = false
    }

    /**
     * 停服务：撤通知、结束服务。撤通知投递到主线程做：状态收集协程的 Notifications.update 也在主线程，
     * 同线程发出的 notify 与 cancel 到达系统的顺序是确定的；在 worker 上撤则可能被晚到的 update 抢在后面，留下撤不掉的通知。
     *
     * [startId] 是触发这次停止的请求的 id。之后又来了新的 START（停止后马上再启动）就不停：服务和通知都留着，
     * 让排在 worker 后面的 startVpn 在这个服务里跑；无条件 stopSelf() 会连新请求一起杀掉。
     * 不能直接 stopSelfResult(startId)：COPY_LOGIN、RESTART 这类辅助请求也会推进 startId，
     * 它们排在 STOP 之后到达时，STOP 自己的 id 就不再是最新的，服务会永远停不掉。所以先看有没有更新的 START，
     * 没有的话用最近一次到达的 id 去停：这时返回 false 只可能是还有没送达的请求，由它自己的处理去停或保留。
     */
    private fun stopService(startId: Int) {
        mainHandler.post {
            if (latestStartRequestId > startId) return@post
            if (stopSelfResult(lastStartId)) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun stopVpn(startId: Int) {
        stopCore()
        stopService(startId)
        Log.i(TAG, "vpn stopped")
    }

    /**
     * 配置或 GeoIP 库换了之后让它生效：只停内核再起，不 stopForeground、不 stopSelf，通知一直保持。
     * 新 fd 由 startVpn 里新的 establish() 产生。
     */
    private fun restartVpn(startId: Int) {
        Log.i(TAG, "vpn restarting")
        stopCore() // running 先落到 false：startVpn 才不会当成「已在运行」，通知观察者也不会把中间的 stopped 刷到通知上
        CoreBridge.markStarting()
        startVpn(startId)
    }

    /** [logLine] 见 [CoreBridge.fail]：[message] 带着异常信息时给一个不含它的日志版本。 */
    private fun failAndStop(message: String, startId: Int, logLine: String = message) {
        unregisterNetworkCallback()
        running = false
        CoreBridge.fail(message, logLine)
        stopService(startId)
    }

    /**
     * 跟踪底层网络（Wi-Fi / 蜂窝）的变化，通知 tsnet 重新探测。
     * 不能用 registerDefaultNetworkCallback：Android 16 / ColorOS 上 VPN App 跟踪到的「默认网络」是它自己的 VPN
     * （即使本 App 已被排除在 VPN 外），切 Wi-Fi 时 VPN 网络不变，回调从不触发。所以显式请求 NOT_VPN 的网络。
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed("available $network")
            override fun onLost(network: Network) = changed("lost $network")
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = changed("link $network")
            private fun changed(why: String) {
                Log.i(TAG, "underlying network $why")
                // debug 包把网络事件另记一份到私有目录：ColorOS 的 logcat 缓冲区只有 256 KiB，切网验证时常被冲掉
                if (BuildConfig.DEBUG) {
                    runCatching { File(filesDir, "netevents.log").appendText("${System.currentTimeMillis()} $why\n") }
                }
                // 不在系统的回调线程里同步调 Go：内核一旦卡住，会堵死整个进程的网络回调
                worker.execute { Ptcore.notifyNetworkChanged() }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        if (Build.VERSION.SDK_INT >= 31) {
            cm.registerBestMatchingNetworkCallback(request, cb, Handler(Looper.getMainLooper()))
        } else {
            cm.registerNetworkCallback(request, cb) // 每个匹配网络各回调一次；我们只关心「有变化」
        }
        netCallback = cb
    }

    private fun unregisterNetworkCallback() {
        netCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        netCallback = null
    }

    override fun onRevoke() {
        Log.i(TAG, "vpn revoked by system")
        val id = lastStartId // onRevoke 在主线程；之后又来的启动请求会让这次 stopSelfResult 返回 false
        worker.execute { stopVpn(id) }
    }

    override fun onDestroy() {
        destroyed = true
        // 不在主线程调 Go（tsnet Close 可能阻塞，会 ANR）：交给 worker。worker 是进程级单线程，
        // 之后新建的服务实例的 startVpn 排在它后面，不会被它误停
        worker.execute { if (running) stopCore() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.robb3n.petrel.START"
        const val ACTION_STOP = "com.robb3n.petrel.STOP"
        const val ACTION_RESTART = "com.robb3n.petrel.RESTART"
        const val ACTION_COPY_LOGIN = "com.robb3n.petrel.COPY_LOGIN"

        /**
         * 所有 Go 调用的串行出口。进程级共用而不是每个服务实例一个：服务停止后马上再启动会新建实例，
         * 旧实例 onDestroy 里排的停止必须先于新实例的启动执行。
         */
        private val worker = Executors.newSingleThreadExecutor()

        /** 调用方需已确认 VpnService.prepare() == null。可能抛 ForegroundServiceStartNotAllowedException。 */
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PetrelVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, PetrelVpnService::class.java).setAction(ACTION_STOP))
        }

        /** VPN 在运行时重启它让新配置生效；服务没在跑时什么也不做。 */
        fun restart(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, PetrelVpnService::class.java).setAction(ACTION_RESTART)) }
                .onFailure { Log.w(TAG, "restart request failed: ${it.javaClass.simpleName}") }
        }
    }
}
