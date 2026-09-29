package com.cs2stats.app.data.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.cs2stats.app.MainActivity
import com.cs2stats.app.R
import com.cs2stats.app.data.local.SettingsStore
import com.cs2stats.app.data.repo.StatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 「下载 demo 并本地解析」的前台服务。
 *
 * 为什么用前台服务：一场 Premier 回放约 **140MB**，下载 + 解析在手机上要 1~3 分钟。
 * 用户必须能退出详情页、锁屏而任务不断 —— 所以按需求做成**前台服务 + 通知进度**，
 * 完成/失败再推一条结果通知。
 *
 * 流程与进度映射：
 *
 * ```
 * 下载 .dem.bz2（复用已下载文件）  0~80%
 * 本机解析 libcs2demo.so(Go)       80~95%
 * 合并 → 写本地缓存 → 归档网盘     95~100%
 * 删除 .bz2（缓存最多留 3 个）
 * ```
 *
 * 取消：`ACTION_CANCEL` → 置取消标志 → 下载循环中断 / `DemoNative.cancel()`，
 * Go 侧正在读的 reader 立刻返回错误，[DemoNative.parseJson] 随即返回 `{"error":"已取消"}`。
 */
class DemoParseService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cancelled = false

    @Volatile
    private var jobRunning = false

    /** 批量队列是否在跑：决定状态里的 `queueActive` 标记（单场解析恒为 false）。 */
    @Volatile
    private var queueRunning = false

    /** 批量队列进度：只由队列协程自己读写。 */
    private var queueIndex = 0
    private var queueTotal = 0

    private var matchId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var http: OkHttpClient? = null

    /** 进度节流：400ms 或 0.5% 才刷一次，避免每 64KB 就发一遍系统通知。 */
    private var lastNotifAt = 0L
    private var lastNotifProgress = -1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                if (DemoNative.available) runCatching { DemoNative.cancel() }
                // 任务在跑就让它自己收尾发布 CANCELLED；没在跑（服务被杀过）才清残留状态
                if (!jobRunning) DemoParseBus.publish(null)
            }

            ACTION_START -> {
                // ⚠️ 必须在**任何**分支 return 之前把前台状态占住。
                // Android 12+ 对 startForegroundService() 有 5 秒强校验，
                // 一旦提前 return，系统会抛 ForegroundServiceDidNotStartInTimeException
                // 把整个 App 崩掉（真机已复现过一次）。
                startAsForeground(buildNotification("准备下载 demo…", 0f))

                val id = intent.getStringExtra(EXTRA_MATCH_ID)
                val url = intent.getStringExtra(EXTRA_URL)
                if (id.isNullOrBlank() || url.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // 只看**服务自身**的在跑标志：不能读 DemoParseBus —— 调用方
                // （AppViewModel）为让 UI 立刻显示进度会先发布占位状态，
                // 读它会让服务误判「已有任务」而跳过启动流程。
                if (jobRunning) return START_NOT_STICKY
                begin(id, url)
            }

            ACTION_START_QUEUE -> {
                // 同样：任何 return 之前先把前台状态占住（Android 12+ 的 5 秒强校验）
                startAsForeground(buildNotification("正在组建解析队列…", 0f))
                // 连点两次时第二次被 jobRunning 拦掉 —— 这个标志必须在**主线程同步**置位，
                // 不能等协程起来再设，否则两个队列会同时跑
                if (jobRunning) return START_NOT_STICKY
                queueRunning = true
                cancelled = false
                jobRunning = true
                lastNotifAt = 0L
                lastNotifProgress = -1f
                acquireWakeLock()
                scope.launch { queueLoop() }
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        scope.cancel()
        releaseWakeLock()
        http?.dispatcher?.cancelAll()
        super.onDestroy()
    }

    // ---------- 主流程 ----------

    private fun begin(id: String, url: String) {
        matchId = id
        cancelled = false
        jobRunning = true
        lastNotifAt = 0L
        lastNotifProgress = -1f
        // 通知/前台状态已由 onStartCommand 占住，这里只管接管任务
        acquireWakeLock()

        scope.launch {
            try {
                val note = runOne(id, url)
                updateState(DemoPhase.DONE, 1f, note)
                notifyResult("Demo 解析完成", note)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                if (cancelled) {
                    updateState(DemoPhase.CANCELLED, DemoParseBus.state.value?.progress ?: 0f, "已取消")
                } else {
                    updateState(DemoPhase.FAILED, DemoParseBus.state.value?.progress ?: 0f, msg)
                    notifyResult("Demo 解析失败", msg)
                }
            } finally {
                jobRunning = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * 单场比赛的完整流程：下载 → 本机解析 → 合并落库 → 归档网盘。
     *
     * **不负责**收尾（不停服务、不发结果通知）——那是调用方的事：
     * 批量队列会连着跑很多场，停服务必须等整队跑完才做。
     *
     * @return 给用户看的完成说明
     * @throws Throwable 下载失败 / 解析失败 / 合并被拒 / 已取消
     */
    private suspend fun runOne(id: String, url: String): String {
        publish(DemoPhase.DOWNLOADING, 0f, null)

        if (!DemoNative.available) {
            throw IOException("当前机型不支持本地解析（只有 arm64-v8a 提供解析库）")
        }

        val demoFile = download(url, id)
        ensureActive()

        val json = parse(demoFile)
        ensureActive()

        updateState(DemoPhase.SAVING, 0.95f, "合并并归档…")
        val note = mergeAndSave(id, json)

        // 解析产物用完即删，本地只留最近 3 个（需求约定）
        demoFile.delete()
        trimCache()

        updateState(DemoPhase.DONE, 1f, note)
        return note
    }

    /**
     * 批量解析队列：一场接一场跑 [runOne]，场与场之间限速等待。
     *
     * 候选见 [DemoQueue.candidates]（示例数据跳过、已解析跳过、**最早的比赛先来**）。
     *
     * 队列的三条脾气：
     * 1. **单场失败不打断整队**（403 过期、磁盘满…记一笔继续下一场）；
     * 2. **取消立刻生效**：正在跑的那场抛错收尾，队列随即 break；
     * 3. 每场之间隔 [QUEUE_GAP_MS]，别把 Valve 回放服务器当自家的用。
     *
     * 跑完发一条汇总通知（成功/失败各几场），并把状态上的 `queueActive` 置回 false。
     */
    private suspend fun queueLoop() {
        var ok = 0
        var failed = 0
        try {
            val items = buildQueue()
            if (items.isEmpty()) {
                val msg = "没有可解析的比赛：回放链接约 30 天过期，或这些比赛都已解析过。"
                DemoParseBus.publish(DemoParseState("", DemoPhase.FAILED, 0f, msg))
                notifyResult("批量解析", msg)
                return
            }
            queueTotal = items.size

            for ((i, item) in items.withIndex()) {
                if (cancelled) break
                queueIndex = i + 1
                matchId = item.first
                // 单次持锁 15 分钟是硬上限，长队列每开一场重新上一次
                acquireWakeLock()
                try {
                    runOne(item.first, item.second)
                    ok++
                } catch (t: Throwable) {
                    val msg = t.message ?: t.javaClass.simpleName
                    if (cancelled) {
                        updateState(DemoPhase.CANCELLED, DemoParseBus.state.value?.progress ?: 0f, "已取消")
                        break
                    }
                    failed++
                    updateState(DemoPhase.FAILED, 0f, msg)
                    // 让失败原因在通知/卡片上停一下，别一闪而过就翻篇
                    delay(FAIL_HOLD_MS)
                }
                if (i < items.lastIndex && !cancelled) delay(QUEUE_GAP_MS)
            }

            val summary = if (cancelled) {
                val rest = (queueTotal - ok - failed).coerceAtLeast(0)
                "已取消：成功 $ok 场、失败 $failed 场，还有 $rest 场没跑。"
            } else {
                "批量解析完成：成功 $ok 场、失败 $failed 场。"
            }
            // 收尾状态：queueActive=false，页面据此从「进行中」切回按钮
            publishSummary(if (cancelled) DemoPhase.CANCELLED else DemoPhase.DONE, summary)
            notifyResult("批量解析结束", summary)
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            publishSummary(DemoPhase.FAILED, msg)
            notifyResult("批量解析失败", msg)
        } finally {
            queueRunning = false
            jobRunning = false
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 从数据空间（本地 ∪ 网盘）挑出还没解析、回放链接还在的比赛。 */
    private suspend fun buildQueue(): List<Pair<String, String>> {
        val repo = StatsRepository(SettingsStore(applicationContext), filesDir)
        val settings = repo.settings.first()
        val data = repo.load(settings)
        return DemoQueue.candidates(data.matches, data.isSample)
            .mapNotNull { m -> m.replayUrl?.let { m.id to it } }
    }

    /** 队列跑完（或中途出错）时发的**最后一条**状态：队列标记在这里落回 false。 */
    private fun publishSummary(phase: DemoPhase, message: String) {
        queueRunning = false
        DemoParseBus.publish(
            DemoParseState(
                matchId = matchId.orEmpty(),
                phase = phase,
                progress = 1f,
                message = message,
                queueIndex = queueIndex,
                queueTotal = queueTotal,
                queueActive = false,
            ),
        )
    }

    private fun ensureActive() {
        if (cancelled) throw InterruptedIOException("已取消")
    }

    // ---------- 1) 下载 ----------

    private fun download(url: String, id: String): File {
        val dir = File(cacheDir, "demo").apply { mkdirs() }
        val target = File(dir, "$id.dem.bz2")
        // 已经下好（上次解析失败/被取消后重试）就复用，不重复拉流量
        if (target.exists() && target.length() > 1024) {
            updateState(DemoPhase.DOWNLOADING, 0.8f, "复用已下载的 demo（${mb(target.length())} MB）")
            return target
        }

        updateState(DemoPhase.DOWNLOADING, 0.01f, "连接 Valve 回放服务器…")
        val part = File(dir, "$id.dem.bz2.part")
        val client = http ?: OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
            .also { http = it }

        // 取消 / 断流 / 非 2xx 都会从这里抛出去：`.part` 没有断点续传价值
        // （下次直接整份重下），留着就是纯垃圾 —— 真机实测取消一次留下过 50MB 孤儿。
        // 完整的 `target` 则保留，供下次重试复用，不必再拉一次流量。
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val hint = when (resp.code) {
                        403, 404, 410 -> "（回放链接已过期，Valve 只保留约 30 天）"
                        else -> ""
                    }
                    throw IOException("下载失败：HTTP ${resp.code}$hint")
                }
                val body = resp.body ?: throw IOException("下载失败：服务器没有返回内容")
                val total = body.contentLength()
                var read = 0L
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val frac = read.toFloat() / total
                                publishThrottled(
                                    DemoPhase.DOWNLOADING, 0.8f * frac,
                                    "下载 ${mb(read)}/${mb(total)} MB（${(frac * 100).roundToInt()}%）",
                                )
                            } else {
                                publishThrottled(DemoPhase.DOWNLOADING, 0f, "下载 ${mb(read)} MB")
                            }
                        }
                    }
                }
                if (total > 0 && read != total) throw IOException("下载中断（已收 ${mb(read)}/${mb(total)} MB）")
                if (read < 1024) throw IOException("下载失败：文件过小，回放可能已失效")
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        return target
    }

    // ---------- 2) 解析 ----------

    /**
     * [DemoNative.parseJson] 是阻塞 JNI 调用，放独立线程执行；本线程每 300ms
     * 轮询 Go 侧的原子进度，让通知与页面跟着走。返回的字节是 UTF-8 JSON，
     * 失败时形如 `{"version":1,"error":"..."}`。
     */
    private fun parse(file: File): String {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "demo-parse") }
        try {
            val future = pool.submit<String> {
                String(DemoNative.parseJson(file.absolutePath), Charsets.UTF_8)
            }
            while (!future.isDone) {
                if (cancelled) runCatching { DemoNative.cancel() }
                val perMille = DemoNative.progress()
                publishThrottled(
                    DemoPhase.PARSING,
                    0.8f + 0.15f * (perMille / 10000f),
                    "解析中 ${(perMille / 100f).roundToInt()}%（本机 demoinfocs）",
                )
                Thread.sleep(300)
            }
            return future.get()
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------- 3) 合并 + 归档 ----------

    private suspend fun mergeAndSave(id: String, json: String): String {
        val repo = StatsRepository(SettingsStore(applicationContext), filesDir)
        val settings = repo.settings.first()
        val local = repo.loadLocalMatches()

        // 比赛必须是**真实采集**来的：示例数据绝不进本地缓存
        var target = local.firstOrNull { it.id == id }
        if (target == null) {
            val data = runCatching { repo.load(settings) }.getOrNull()
                ?: throw IOException("找不到这场比赛（本地缓存与数据空间都没有）")
            if (data.isSample) throw IOException("这场比赛只存在于示例数据中，不能写入采集缓存")
            target = data.matches.firstOrNull { it.id == id }
                ?: throw IOException("找不到这场比赛（本地缓存与数据空间都没有）")
        }

        val outcome = DemoStatsMerger.merge(target, json)
        val base = if (local.any { it.id == id }) local else local + outcome.match
        repo.saveLocalMatches(base.map { if (it.id == id) outcome.match else it })

        if (!settings.hasWebDav) {
            return "${outcome.note} · 已保存到本地（网盘未配置，未归档）"
        }
        // 自动归档到数据空间（需求：解析结果自动上传归档）
        return runCatching {
            val data = repo.load(settings)   // 本地 ∪ 网盘，避免覆盖别处更新的数据
            val matches = data.matches.map { if (it.id == id) outcome.match else it }
            repo.uploadToCloud(settings, data.profile, matches)
        }.fold(
            onSuccess = { "${outcome.note} · $it" },
            onFailure = { "${outcome.note} · 归档网盘失败：${it.message}" },
        )
    }

    // ---------- 状态 / 通知 ----------

    /**
     * 队列里给文案加「第 i/N 场 · 」前缀；单场解析（`queueTotal == 0`）原样返回。
     * 统一在这里加，下载/解析/合并每一句提示就都自带上下文了。
     */
    private fun qmsg(message: String?): String? =
        if (message == null || queueTotal <= 0) message
        else "第 $queueIndex/$queueTotal 场 · $message"

    /** 发布状态（带队列下标与队列标记），不刷通知。 */
    private fun publish(phase: DemoPhase, progress: Float, message: String?) {
        val id = matchId ?: return
        DemoParseBus.publish(
            DemoParseState(
                matchId = id,
                phase = phase,
                progress = progress,
                message = qmsg(message),
                queueIndex = queueIndex,
                queueTotal = queueTotal,
                queueActive = queueRunning,
            ),
        )
    }

    private fun updateState(phase: DemoPhase, progress: Float, message: String?) {
        publish(phase, progress, message)
        val id = matchId ?: return
        notifyProgress(qmsg(message) ?: phase.name, progress)
    }

    private fun publishThrottled(phase: DemoPhase, progress: Float, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifAt < 400 && progress - lastNotifProgress < 0.005f) return
        publish(phase, progress, message)
        val id = matchId ?: return
        notifyProgress(qmsg(message) ?: message, progress)
    }

    private fun notifyProgress(text: String, progress: Float) {
        lastNotifAt = System.currentTimeMillis()
        lastNotifProgress = progress
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, progress))
    }

    private fun notifyResult(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(
                NOTIF_RESULT_ID,
                buildNotification(title, 0f, bigText = text, ongoing = false, showProgress = false),
            )
    }

    private fun buildNotification(
        text: String,
        progress: Float,
        bigText: String? = null,
        ongoing: Boolean = true,
        showProgress: Boolean = true,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notif_demo_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(ongoing)
        if (showProgress) {
            b.setProgress(100, (progress.coerceIn(0f, 1f) * 100).roundToInt(), false)
        }
        if (bigText != null) {
            b.setStyle(Notification.BigTextStyle().bigText(bigText))
        }
        return b.build()
    }

    // ---------- 前台 / 唤醒锁 ----------

    private fun startAsForeground(notification: Notification) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_demo_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cs2Stats:demoParse").apply {
            setReferenceCounted(false)
            acquire(15 * 60 * 1000L)   // 硬上限 15 分钟，异常时也不会永久持锁
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun trimCache() {
        val dir = File(cacheDir, "demo")
        // 旧版本取消下载时漏下的 `.part` 半截文件顺手清掉（同一时刻只有一个任务在跑，
        // 不会有正在写的 part）；下面的 `.dem.bz2` 再按份数保留。
        dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }
            ?.forEach { it.delete() }

        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".dem.bz2") }
            ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_CACHED_DEMOS)
            .forEach { it.delete() }
    }

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)

    companion object {
        const val ACTION_START = "com.cs2stats.app.demo.START"
        const val ACTION_START_QUEUE = "com.cs2stats.app.demo.START_QUEUE"
        const val ACTION_CANCEL = "com.cs2stats.app.demo.CANCEL"
        const val EXTRA_MATCH_ID = "matchId"
        const val EXTRA_URL = "url"

        private const val CHANNEL_ID = "demo_parse"
        private const val NOTIF_ID = 4201
        private const val NOTIF_RESULT_ID = 4202
        private const val MAX_CACHED_DEMOS = 3

        /** 场与场之间的限速间隔：别把 Valve 回放服务器当自家的用。 */
        private const val QUEUE_GAP_MS = 3_000L
        /** 单场失败后停留多久再进下一场（让失败原因来得及看清）。 */
        private const val FAIL_HOLD_MS = 1_500L

        /** 启动解析（必须在前台调用，Android 12+ 不允许后台启前台服务）。 */
        fun start(context: Context, id: String, url: String) {
            val intent = Intent(context, DemoParseService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MATCH_ID, id)
                putExtra(EXTRA_URL, url)
            }
            context.startForegroundService(intent)
        }

        /**
         * 启动批量解析队列。候选比赛由**服务自己**去数据空间里挑
         * （本地 ∪ 网盘可能比页面还新），调用方不用传清单。
         */
        fun startQueue(context: Context) {
            val intent = Intent(context, DemoParseService::class.java).apply {
                action = ACTION_START_QUEUE
            }
            context.startForegroundService(intent)
        }

        /** 取消当前解析。 */
        fun cancel(context: Context) {
            val intent = Intent(context, DemoParseService::class.java).apply {
                action = ACTION_CANCEL
            }
            runCatching { context.startService(intent) }
                .onFailure { DemoParseBus.publish(null) }   // 服务没起来就直接清状态
        }
    }
}
