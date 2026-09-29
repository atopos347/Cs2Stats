package com.cs2stats.app.data.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 解析任务阶段。 */
enum class DemoPhase {
    DOWNLOADING,   // 下载 demo（Valve 回放服务器）
    PARSING,       // 本机解析（demoinfocs）
    SAVING,        // 合并结果 → 写本地 → 归档网盘
    DONE,
    FAILED,
    CANCELLED,
}

/** 一次解析任务的状态快照（全局单例：服务是进程级的，页面退出也能继续）。 */
data class DemoParseState(
    val matchId: String,
    val phase: DemoPhase,
    /** 0..1，跨阶段的整体进度：下载占 0~0.8，解析占 0.8~0.95，保存占 0.95~1。 */
    val progress: Float,
    /** 完成后的说明 / 失败原因。 */
    val message: String? = null,
    /** 批量解析：当前是第几场（单场解析为 0）。 */
    val queueIndex: Int = 0,
    /** 批量解析：队列总场数（单场解析为 0）。 */
    val queueTotal: Int = 0,
    /**
     * 批量队列**是否仍在跑**。服务在每条状态上都带上它，并在最后一条
     * （汇总）状态里置回 `false` —— 页面据此区分「进行中 3/12」和「跑完了」，
     * 不用去数队列下标（取消/失败时下标还没走满，光看下标会误判）。
     */
    val queueActive: Boolean = false,
)

/**
 * 解析任务状态总线：`DemoParseService` 写、任意页面读。
 *
 * 用全局单例而不是把状态塞进 ViewModel，是因为任务跑在前台服务里，
 * **配置变化（旋转屏幕）、甚至退出详情页都不该中断解析**。
 */
object DemoParseBus {

    private val _state = MutableStateFlow<DemoParseState?>(null)
    val state: StateFlow<DemoParseState?> = _state.asStateFlow()

    fun publish(state: DemoParseState?) {
        _state.value = state
    }

    /** 当前是否正在跑（服务重启时据此去重）。 */
    fun isRunning(matchId: String): Boolean {
        val s = _state.value
        return s != null && s.matchId == matchId &&
            s.phase in setOf(DemoPhase.DOWNLOADING, DemoPhase.PARSING, DemoPhase.SAVING)
    }

    /** 批量队列是否还在跑（再点一次「批量解析」要被拦掉）。 */
    fun isQueueActive(): Boolean = _state.value?.queueActive == true
}
