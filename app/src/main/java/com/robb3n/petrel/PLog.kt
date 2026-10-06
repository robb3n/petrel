package com.robb3n.petrel

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 日志门面：Kotlin 侧的日志一律走这里，不直接调 android.util.Log。
 * 照旧打 logcat（tag [TAG]），同时按 [shouldPersist] 把行写进 `files/logs/petrel.log`（轮转见 [RotatingLogFile]）。
 *
 * 调用方线程上不做文件 IO：Go 的 Host.Log 可能在 tailscale 持锁时回调进来，还有主线程与系统回调线程上的调用。
 * 所以只把格式化好的行 offer 进有界队列（满了丢弃并计数），由守护线程 `petrel-log` 写文件。唯一的例外是 [fatal]。
 * 文件侧的异常只记类名链（[throwableChain]），不记 message 与栈。
 */
object PLog {
    private const val QUEUE_CAPACITY = 4096
    /** 写失败后隔这么久才再试，免得磁盘满时每行都抛一次。 */
    private const val RETRY_AFTER_MS = 60_000L

    /** 队列元素：String 是一行；CountDownLatch 是 [flush] 的标记。 */
    private val queue = ArrayBlockingQueue<Any>(QUEUE_CAPACITY)
    private val dropped = AtomicInteger()

    /** 写线程与 [fatal] 共用这把锁和同一个流，行不交错。 */
    private val lock = Any()
    private var file: RotatingLogFile? = null
    private var failedAt = 0L
    private var failureReported = false

    @Volatile
    private var dir: File? = null

    fun logDir(ctx: Context): File = File(ctx.filesDir, "logs")

    /** `App.onCreate` 最先调用；之前的调用只打 logcat。 */
    fun init(ctx: Context, versionName: String, versionCode: Int) {
        if (dir != null) return
        synchronized(lock) { file = RotatingLogFile(logDir(ctx)) } // 只建对象，目录与流在写线程上第一次写时才建
        dir = logDir(ctx)
        Thread(::drain, "petrel-log").apply { isDaemon = true }.start()
        enqueue('I', "=== process start v$versionName ($versionCode) ===")
    }

    fun d(msg: String, tr: Throwable? = null) {
        if (tr == null) Log.d(TAG, msg) else Log.d(TAG, msg, tr)
        enqueue('D', msg, tr)
    }

    fun i(msg: String, tr: Throwable? = null) {
        if (tr == null) Log.i(TAG, msg) else Log.i(TAG, msg, tr)
        enqueue('I', msg, tr)
    }

    fun w(msg: String, tr: Throwable? = null) {
        if (tr == null) Log.w(TAG, msg) else Log.w(TAG, msg, tr)
        enqueue('W', msg, tr)
    }

    fun e(msg: String, tr: Throwable? = null) {
        if (tr == null) Log.e(TAG, msg) else Log.e(TAG, msg, tr)
        enqueue('E', msg, tr)
    }

    /** 等写线程把此刻之前进队的行写完，最多 [timeoutMs]。超时返回 false（导出照现状进行）。 */
    fun flush(timeoutMs: Long = 500): Boolean {
        if (dir == null) return true
        val latch = CountDownLatch(1)
        if (!queue.offer(latch, timeoutMs, TimeUnit.MILLISECONDS)) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * 未捕获异常：在崩溃线程上**同步**写。先把队列里剩下的行写掉，再写 [fatalLines]。
     * 不打 logcat：随后交回的原 handler 会照常记 FATAL EXCEPTION。
     */
    fun fatal(thread: Thread, e: Throwable) {
        if (dir == null) return
        val pid = Process.myPid()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            while (true) {
                when (val item = queue.poll() ?: break) {
                    is String -> writeLocked(item)
                    is CountDownLatch -> item.countDown()
                }
            }
            fatalLines(thread.name, e).forEach { writeLocked(formatLine(now, pid, 'E', it)) }
        }
    }

    private fun enqueue(level: Char, msg: String, tr: Throwable? = null) {
        if (dir == null || !shouldPersist(level, msg)) return
        val text = if (tr == null) msg else "$msg (${throwableChain(tr)})"
        if (!queue.offer(formatLine(System.currentTimeMillis(), Process.myPid(), level, text))) dropped.incrementAndGet()
    }

    private fun drain() {
        while (true) {
            when (val item = queue.take()) {
                is String -> synchronized(lock) { writeLocked(item) }
                is CountDownLatch -> item.countDown()
            }
        }
    }

    /** 持 [lock] 调用。失败时 logcat 只记一次，之后 [RETRY_AFTER_MS] 内的行直接丢掉。 */
    private fun writeLocked(line: String) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (failedAt != 0L && now - failedAt < RETRY_AFTER_MS) return
        try {
            val n = dropped.getAndSet(0)
            if (n > 0) f.write(formatLine(now, Process.myPid(), 'W', "dropped $n lines"))
            f.write(line)
            failedAt = 0L
        } catch (t: Throwable) {
            f.close()
            failedAt = now
            if (!failureReported) {
                failureReported = true
                Log.w(TAG, "log file write failed: ${t.javaClass.simpleName}")
            }
        }
    }
}
