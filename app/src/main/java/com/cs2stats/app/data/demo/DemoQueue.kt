package com.cs2stats.app.data.demo

import com.cs2stats.app.data.model.MatchDetail

/**
 * 批量解析队列的**候选筛选**（纯函数，方便单测）。
 *
 * 规则：
 *
 * 1. 示例数据整体不进队列——假比赛没有回放，点了只会白跑一趟；
 * 2. 只收 `replayUrl` 还在的（Valve 约 30 天过期，过期的下下来也是 403）；
 * 3. 已解析过的（`demoParsedAt != null`）跳过，不重复下载几百 MB；
 * 4. **按开始时间从早到晚**排队：越早的比赛越接近过期，先救它们；
 *    越新的越不容易在队列里等到过期，放后面也无所谓。
 *
 * 失败不在此处处理：某一场 403 / 磁盘满都由服务记录后**继续跑下一场**，
 * 队列只对「哪些比赛值得排队」下判断。
 */
object DemoQueue {

    /** @param isSample 当前数据是否为示例数据（示例数据一律返回空队列） */
    fun candidates(matches: List<MatchDetail>, isSample: Boolean): List<MatchDetail> =
        if (isSample) {
            emptyList()
        } else {
            matches
                .filter { !it.replayUrl.isNullOrBlank() && it.demoParsedAt == null }
                .sortedBy { it.startedAt }
        }

    /** 队列候选场数（给按钮/提示用）。 */
    fun count(matches: List<MatchDetail>, isSample: Boolean): Int =
        candidates(matches, isSample).size
}
