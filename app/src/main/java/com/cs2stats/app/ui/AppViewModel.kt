package com.cs2stats.app.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cs2stats.app.data.collect.SteamMatchCollector
import com.cs2stats.app.data.demo.DemoNative
import com.cs2stats.app.data.demo.DemoParseBus
import com.cs2stats.app.data.demo.DemoParseService
import com.cs2stats.app.data.demo.DemoParseState
import com.cs2stats.app.data.demo.DemoPhase
import com.cs2stats.app.data.demo.DemoStatsMerger
import com.cs2stats.app.data.local.AppSettings
import com.cs2stats.app.data.local.SettingsStore
import com.cs2stats.app.data.mock.MockData
import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.model.OverallStats
import com.cs2stats.app.data.remote.SteamProfileFetcher
import com.cs2stats.app.data.repo.LoadResult
import com.cs2stats.app.data.repo.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面状态。 */
sealed interface UiState {
    data object Loading : UiState
    data class Ready(
        val data: LoadResult,
        val overall: OverallStats,
        val mySteamId: String,
    ) : UiState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = StatsRepository(SettingsStore(app), app.filesDir)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ui = MutableStateFlow<UiState>(UiState.Loading)
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 一次性提示（测试连接 / 上传结果），UI 消费后调用 [clearNotice]。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    init {
        viewModelScope.launch {
            repo.settings.collect { s ->
                _settings.value = s
                load(s)
            }
        }
        // 解析完成 → 重新读本地缓存，让详情页/总览立刻看到补齐的字段。
        // 失败与取消不刷新（页面就地显示原因，避免无谓的整表重载）。
        viewModelScope.launch {
            DemoParseBus.state.collect { s ->
                if (s?.phase == DemoPhase.DONE) {
                    _notice.value = s.message ?: "Demo 解析完成"
                    load(_settings.value)
                }
            }
        }
    }

    // ---------- 本机 demo 解析（前台服务） ----------

    /** 解析进度：跑在服务里，配置变化、退出详情页都不中断，页面回来照样读得到。 */
    val demoState: StateFlow<DemoParseState?> = DemoParseBus.state

    /**
     * 启动「下载 demo 并本地解析」。前置条件都在这里拦掉并给出原因，
     * 界面不摆假按钮（过期 / 非 arm64 由详情页自己判断）。
     */
    fun startDemoParse(match: MatchDetail) {
        val url = match.replayUrl
        if (url.isNullOrBlank()) {
            _notice.value = "这场比赛的 demo 链接已过期（Valve 只保留约 30 天）。"
            return
        }
        if (!DemoNative.available) {
            _notice.value = "当前机型不支持本地解析（只有 arm64-v8a 提供解析库）。"
            return
        }
        if (DemoParseBus.isRunning(match.id)) return
        // 先占位再拉服务：用户立刻就能看到「下载中 0%」，而不是等服务起来
        DemoParseBus.publish(DemoParseState(match.id, DemoPhase.DOWNLOADING, 0f, "正在启动服务…"))
        runCatching { DemoParseService.start(getApplication(), match.id, url) }
            .onFailure {
                DemoParseBus.publish(
                    DemoParseState(match.id, DemoPhase.FAILED, 0f, it.message ?: "启动服务失败"),
                )
            }
    }

    /** 取消当前解析（服务在跑就让它自己收尾；不在跑就清状态）。 */
    fun cancelDemoParse() {
        DemoParseService.cancel(getApplication())
    }

    /**
     * 当前网络类型，只用来在**批量下载前**把流量风险摆在用户面前
     * （"Wi-Fi" / "移动数据" / "无网络"…）。
     *
     * 不做硬拦截：用户已经在确认框里读过流量提示了，连移动数据也要解析是他的自由，
     * 替用户按「禁止」反而会在没有 Wi-Fi 的场景下把功能锁死。
     */
    fun networkType(): String {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return "无网络"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
            else -> "其他网络"
        }
    }

    /**
     * 启动批量解析队列：把「有回放链接、还没解析」的比赛按**最早→最新**排队，
     * 一场接一场跑（候选筛选见 [DemoQueue]，由服务在数据空间里再筛一遍）。
     *
     * 单场解析入口在详情页不受影响；两条入口共用同一个服务，谁先抢到 `jobRunning`
     * 谁跑，另一条被静默忽略。
     */
    fun startDemoQueue() {
        if (!DemoNative.available) {
            _notice.value = "当前机型不支持本地解析（只有 arm64-v8a 提供解析库）。"
            return
        }
        if (DemoParseBus.isQueueActive()) return
        // 先占位再拉服务：确认框点完立刻看到「正在组建解析队列…」
        DemoParseBus.publish(
            DemoParseState("", DemoPhase.DOWNLOADING, 0f, "正在组建解析队列…", queueActive = true),
        )
        runCatching { DemoParseService.startQueue(getApplication()) }
            .onFailure {
                DemoParseBus.publish(
                    DemoParseState("", DemoPhase.FAILED, 0f, it.message ?: "启动服务失败"),
                )
            }
    }

    /**
     * 重新从数据源拉取（设置页「下载同步」）。
     * [transform] 用于把页面上还没落盘的输入一并带上，避免异步保存的竞态。
     */
    fun refresh(transform: (AppSettings) -> AppSettings = { it }) {
        val s = transform(_settings.value)
        viewModelScope.launch { repo.updateSettings(transform) }
        viewModelScope.launch { load(s) }
    }

    /** 测试网盘连通性与凭据。 */
    fun testCloud(transform: (AppSettings) -> AppSettings = { it }) {
        val s = transform(_settings.value)
        viewModelScope.launch { repo.updateSettings(transform) }
        viewModelScope.launch {
            _notice.value = "正在连接网盘…"
            _notice.value = withContext(Dispatchers.IO) {
                runCatching {
                    if (repo.testCloud(s)) "✅ 连接成功：${s.davUrl}" else "❌ 连接失败，检查地址与凭据"
                }.getOrElse { "❌ " + (it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    /** 把当前数据集写入网盘（归档 → 上传）。 */
    fun uploadToCloud(transform: (AppSettings) -> AppSettings = { it }) {
        val s = transform(_settings.value)
        val ready = _ui.value as? UiState.Ready
        viewModelScope.launch { repo.updateSettings(transform) }
        if (ready == null) {
            _notice.value = "数据还没加载完成。"
            return
        }
        viewModelScope.launch {
            _notice.value = "正在上传…"
            _notice.value = withContext(Dispatchers.IO) {
                runCatching {
                    repo.uploadToCloud(
                        s = s,
                        profile = ready.data.profile,
                        matches = ready.data.matches,
                    )
                }.getOrElse { "❌ 上传失败：" + (it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    /** 修改设置；Flow 变化会自动触发重新加载。 */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.updateSettings(transform) }
    }

    fun matchById(state: UiState, id: String): MatchDetail? =
        (state as? UiState.Ready)?.data?.matches?.firstOrNull { it.id == id }

    // ---------- 官匹数据采集 ----------

    private val collector by lazy { SteamMatchCollector(getApplication<Application>()) }

    /**
     * 用 Steam 登录态拉「我的游戏数据」的比赛记录（`ajax=1` 分页接口）。
     *
     * 网络/会话异常都会转成可读提示，不抛给 UI。
     * 当前已能拉到比赛条目（时间 + demo 主键 + 排位标记），地图/比分/击杀字段
     * 等拿到真实页面样本校准选择器后才入库——宁可少显示，也不编数据。
     */
    fun collectFromSteam() {
        viewModelScope.launch {
            _notice.value = "正在抓取 Steam 比赛记录…"
            val ready = _ui.value as? UiState.Ready
            val myId = ready?.mySteamId.orEmpty()
            val myName = ready?.data?.profile?.personaName.orEmpty()

            val results = try {
                withContext(Dispatchers.IO) { collector.collect(myId, myName) }
            } catch (e: Exception) {
                _notice.value = "❌ " + (e.message ?: e.javaClass.simpleName)
                return@launch
            }

            val all = results.flatMap { it.matches }
            val detail = results.joinToString("\n") {
                "${it.tab}：${it.note}（${(it.bytes + 1023) / 1024} KB）"
            }

            if (all.isEmpty()) {
                _notice.value = "⚠ 各标签页有响应，但没解析出比赛记录（结构待校准）。\n$detail"
                return@launch
            }

            val total = mergeCollected(all)
            _notice.value = buildString {
                append("✅ 抓到 ${all.size} 场，去重后列表共 $total 场。")
                append("\n")
                append(detail)
                append("\n（伤害/KAST 等字段官匹页面不提供，Rating 已按缺失自动降级；")
                append("解析过的比赛会保留其 demo 成果，不会被本次抓取冲掉）")
            }
        }
    }

    /**
     * 合并新抓到的比赛并重算总览，返回合并后的场次。
     *
     * 当前底座若是**示例数据**（`isSample`，种子生成、日期贴近今天），则**整体替换**——
     * 否则会出现“今天没打过某张图，列表里却有”的假数据。合并结果同时落进本地采集缓存，
     * 重启/断网也能看到，之后再由「上传到网盘」归档。
     */
    private fun mergeCollected(fetched: List<MatchDetail>): Int {
        val cur = _ui.value as? UiState.Ready ?: return fetched.size
        val replacing = cur.data.isSample
        val base = if (replacing) emptyList() else cur.data.matches
        val byId = base.associateBy { it.id }

        // 新抓的赢 —— 但先把**已有的 demo 解析成果**搬到新记录上。
        // 页面记录里伤害=0、KAST/多杀/开局杀=null、demoParsedAt=null，
        // 直接 distinctBy 会把一场 142 MB 换来的解析结果整份冲掉。
        val carried = fetched.map { DemoStatsMerger.carryDemoFields(it, byId[it.id]) }

        val merged = (carried + base)
            .distinctBy { it.id }
            .sortedByDescending { it.startedAt }

        val data = cur.data.copy(
            matches = merged,
            sourceLabel = if (replacing) {
                "本地采集 ${merged.size} 场（示例数据已丢弃）"
            } else {
                "${cur.data.sourceLabel} · 已并入 Steam 采集"
            },
            isSample = false,
        )
        val overall = StatsRepository.buildOverall(merged, cur.mySteamId)
        _ui.value = cur.copy(data = data, overall = overall)

        viewModelScope.launch { repo.saveLocalMatches(merged) }
        return merged.size
    }

    /**
     * Steam OpenID 授权成功：回填 SteamID64，并尽量拉取昵称/头像
     * （资料设为公开才能取到；取不到不阻塞登录）。
     */
    fun onSteamLogin(steamId: String) {
        viewModelScope.launch {
            val fetched = withContext(Dispatchers.IO) { SteamProfileFetcher.fetch(steamId) }
            updateSettings { s ->
                s.copy(
                    steamId = steamId,
                    steamName = fetched?.personaName?.takeIf { it.isNotBlank() } ?: s.steamName,
                    avatarUrl = fetched?.avatarUrl?.takeIf { it.isNotBlank() } ?: s.avatarUrl,
                )
            }
        }
    }

    private suspend fun load(s: AppSettings) {
        _ui.value = UiState.Loading
        val result = withContext(Dispatchers.IO) { repo.load(s) }
        val myId = s.steamId.ifBlank {
            result.profile?.steamId?.ifBlank { MockData.MY_STEAM_ID } ?: MockData.MY_STEAM_ID
        }
        val overall = withContext(Dispatchers.Default) {
            StatsRepository.buildOverall(result.matches, myId)
        }
        _ui.value = UiState.Ready(result, overall, myId)
    }
}
