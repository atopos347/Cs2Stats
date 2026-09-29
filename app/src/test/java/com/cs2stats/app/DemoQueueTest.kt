package com.cs2stats.app

import com.cs2stats.app.data.demo.DemoQueue
import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量解析队列的候选筛选（[DemoQueue]）。
 *
 * 这几条决定「点一次批量按钮会下载什么」，所以锁死：
 * 示例数据不进队列、没链接的不进、已解析的不进、最早的比赛排最前。
 */
class DemoQueueTest {

    private fun match(
        id: String,
        startedAt: Long,
        url: String? = "https://replay403.valve.net/730/$id.dem.bz2",
        parsedAt: Long? = null,
    ): MatchDetail = MatchDetail(
        id = id,
        mapName = "de_ancient",
        mode = "Premier",
        startedAt = startedAt,
        myTeam = Team.CT,
        myScore = 13,
        enemyScore = 11,
        players = emptyList(),
        replayUrl = url,
        demoParsedAt = parsedAt,
    )

    @Test
    fun `示例数据一律不进队列`() {
        val list = listOf(
            match("m1", 1_000L),
            match("m2", 2_000L),
        )
        assertTrue("示例数据没有真实回放", DemoQueue.candidates(list, isSample = true).isEmpty())
        assertEquals(0, DemoQueue.count(list, isSample = true))
    }

    @Test
    fun `没回放链接或已解析的比赛被跳过`() {
        val list = listOf(
            match("expired", 1_000L, url = null),          // 回放已过期
            match("blank", 2_000L, url = "  "),             // 空白链接同样不收
            match("done", 3_000L, parsedAt = 1_700_000_000_000L), // 解析过，别再下一遍
            match("todo", 4_000L),                          // 唯一的候选
        )
        val got = DemoQueue.candidates(list, isSample = false)
        assertEquals(listOf("todo"), got.map { it.id })
        assertEquals(1, DemoQueue.count(list, isSample = false))
    }

    @Test
    fun `最早的比赛排最前`() {
        val list = listOf(
            match("newest", 9_000L),
            match("oldest", 1_000L),
            match("middle", 5_000L),
        )
        assertEquals(listOf("oldest", "middle", "newest"), DemoQueue.candidates(list, isSample = false).map { it.id })
    }

    @Test
    fun `筛选不改动入参`() {
        val list = listOf(match("b", 2_000L), match("a", 1_000L))
        DemoQueue.candidates(list, isSample = false)
        assertEquals(listOf("b", "a"), list.map { it.id })
    }
}
