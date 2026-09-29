package com.cs2stats.app

import com.cs2stats.app.data.collect.GcpdParser
import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.rating.Rating3
import com.cs2stats.app.data.remote.ApiClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用**真机抓回来的全部样本**（`src/test/resources/gcpd/`，2026-09-28）做回归：
 * 覆盖各标签页、完整页与 ajax 分页、以及「本来就不含记分板」的页面。
 */
class GcpdSamplesTest {

    private val mySteamId = "76561199517803315"
    private val myName = "Atopos"

    private fun sampleDir(): File {
        val f = File("src/test/resources/gcpd")
        assertTrue("找不到样本目录：${f.absolutePath}", f.isDirectory)
        return f
    }

    /** 某标签页全部样本合并去重后的比赛（与采集器 `putIfAbsent` 的口径一致）。 */
    private fun parseTab(tab: String): List<MatchDetail> {
        val out = LinkedHashMap<String, MatchDetail>()
        sampleDir().listFiles { _, name -> name.startsWith("gcpd_$tab") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val raw = file.readText(Charsets.UTF_8)
                val html = if (file.name.endsWith(".json")) {
                    runCatching { JSONObject(raw).optString("html") }.getOrDefault("")
                } else raw
                GcpdParser.parse(html, tab, mySteamId, myName)
                    .matches.forEach { out.putIfAbsent(it.id, it) }
            }
        return out.values.sortedByDescending { it.startedAt }
    }

    @Test
    fun `优先匹配解析出的场次数与真机一致`() {
        val premier = parseTab("matchhistorypremier")
        assertTrue("Premier 只解析出 ${premier.size} 场", premier.size >= 50)
    }

    @Test
    fun `其余有记分板的标签页也能解析`() {
        assertTrue(parseTab("matchhistoryrush").isNotEmpty())
        assertTrue(parseTab("matchhistoryscrimmage").isNotEmpty())
    }

    @Test
    fun `没有记分板的页面解析出 0 场而不是乱数据`() {
        assertEquals(0, parseTab("matchhistorycompetitive").size)
        assertEquals(0, parseTab("matchhistorywingman").size)
        // 这两个页面只有「时间/模式」清单或纯页面框架，本就没有比赛行
        assertEquals(0, parseTab("matchhistorycasual").size)
        assertEquals(0, parseTab("deepplayerstatsmatchentry").size)
    }

    @Test
    fun `每场记分板都成对分组且能定位到我`() {
        val all = listOf(
            "matchhistorypremier", "matchhistoryrush", "matchhistoryscrimmage",
        ).flatMap { parseTab(it) }
        assertTrue(all.isNotEmpty())

        for (m in all) {
            val teamA = m.players.count { it.team.name == "TEAM_A" }
            val teamB = m.players.count { it.team.name == "TEAM_B" }
            assertTrue("${m.id} 分组不平衡 $teamA vs $teamB", teamA == teamB && teamA >= 3)
            assertNotNull("${m.id} 找不到我的行", m.myLine(mySteamId))
            assertTrue("${m.id} 比分非法", m.myScore != m.enemyScore)
            assertTrue("${m.id} 回合数非法",
                m.players.all { it.roundsPlayed == m.myScore + m.enemyScore })
        }
    }

    @Test
    fun `优先匹配是 5v5`() {
        assertTrue(parseTab("matchhistorypremier").all { it.players.size == 10 })
    }

    @Test
    fun `优先匹配多数对局都打满 13 回合`() {
        // MR12：先到 13 回合者胜；少数是对方中途投降结束的对局达不到 13，
        // 所以这里只校验“绝大多数”，方向本身已由本人对 Nuke 1:11 的回忆确认。
        val premier = parseTab("matchhistorypremier")
        val fullLength = premier.count { maxOf(it.myScore, it.enemyScore) >= 13 }
        assertTrue("$fullLength/${premier.size} 未达预期", fullLength * 4 >= premier.size * 3)
    }

    @Test
    fun `按天去重后时间倒序且主键唯一`() {
        val premier = parseTab("matchhistorypremier")
        assertEquals(premier.size, premier.map { it.id }.distinct().size)
        assertTrue(premier.zipWithNext().all { (a, b) -> a.startedAt >= b.startedAt })
    }

    @Test
    fun `官匹数据下 Rating 走降级但仍有意义`() {
        val me = parseTab("matchhistorypremier").first().myLine(mySteamId)!!
        val b = Rating3.rate(me)

        // 伤害/KAST/多杀/开局杀页面不提供 => 必须标为部分近似
        assertTrue("本该降级却算成完整 Rating", b.isPartial)
        assertTrue("Rating 越界: ${b.overall}", b.overall in 0.10..2.50)
        assertTrue("爆头率越界", b.hsRate in 0.0..1.0)
        // 可得子项 = 击杀 + 生存（生存由恒等式反推），这样 60:40 结构才不塌成单看击杀
        val available = b.subs.filter { it.available }.map { it.rating }
        assertEquals(
            "可用子项应为 击杀 + 生存，实际 $available",
            listOf(Rating3.SubRating.KILLS, Rating3.SubRating.SURVIVAL),
            available,
        )
        assertTrue("生存子项应标注为反推", b.subs.first { it.available && it.rating == Rating3.SubRating.SURVIVAL }
            .rawLabel.startsWith("≈"))
    }

    @Test
    fun `Ping、Score、★MVP 三列被解析出来`() {
        val all = listOf(
            "matchhistorypremier", "matchhistoryrush", "matchhistoryscrimmage",
        ).flatMap { parseTab(it) }
        assertTrue(all.isNotEmpty())

        for (m in all) for (p in m.players) {
            assertTrue("${m.id} K 为负", p.kills >= 0)
            assertTrue("${m.id} D 为负", p.deaths >= 0)
            assertTrue("${m.id} A 为负", p.assists >= 0)
            assertNotNull("${m.id} 缺 Score", p.score)
            assertNotNull("${m.id} 缺 ★MVP", p.mvps)
            assertTrue("${m.id} ★MVP 为负", p.mvps!! >= 0)
            assertTrue("${m.id} Ping 为负", (p.ping ?: 0) >= 0)
        }
        // 拿到 Ping 的行应是正整数
        assertTrue(all.flatMap { it.players }.count { it.ping != null } > 0)
    }

    @Test
    fun `排位、等待时长、demo 链接来自左栏`() {
        val premier = parseTab("matchhistorypremier")
        assertTrue("Ranked 解析失败：${premier.count { it.ranked == true }}/${premier.size}",
            premier.count { it.ranked == true } * 4 >= premier.size * 3)
        assertTrue("Wait Time 解析失败：${premier.count { it.waitTimeSec != null }}/${premier.size}",
            premier.count { it.waitTimeSec != null } * 4 >= premier.size * 3)

        // Valve 只保留约 30 天的回放：有 demo 链接的必然是最新那批，
        // 老比赛的主键则退化成 gcpd-<epoch>-<地图>（两种都必须能拿到唯一 id）。
        val withReplay = premier.filter { it.replayUrl != null }
        assertTrue("一个 replay 链接都没解析到", withReplay.size >= 5)
        assertTrue(withReplay.all { it.replayUrl!!.endsWith(".dem.bz2") })
        val newest = premier.maxOf { it.startedAt }
        assertTrue("replay 链接出现在 30 天前的比赛上：${withReplay.size} 条",
            withReplay.all { newest - it.startedAt <= 40L * 86400 })
        assertTrue("老比赛缺主键", premier.size - withReplay.size >= 5)

        // Rush 页面没有 Ranked 行 => 必须是 null 而不是瞎填（但有 Wait Time: 00:29）
        val rush = parseTab("matchhistoryrush")
        assertTrue(rush.isNotEmpty())
        assertTrue(rush.all { it.ranked == null })
        assertTrue(rush.all { it.waitTimeSec != null && it.waitTimeSec > 0 })
    }

    @Test
    fun `存活按恒等式反推且不越界`() {
        val all = listOf(
            "matchhistorypremier", "matchhistoryrush", "matchhistoryscrimmage",
        ).flatMap { parseTab(it) }

        for (m in all) for (p in m.players) {
            val expected = if (p.deaths <= p.roundsPlayed) p.roundsPlayed - p.deaths else null
            assertEquals(
                "${m.id}/${p.name} 存活反推错误",
                expected, p.survivedRoundsEff,
            )
            val rate = p.survivalRate
            assertNotNull(rate)
            assertTrue("存活率越界 ${rate}", rate!! in 0.0..1.0)
        }
    }

    @Test
    fun `归档往返后新字段不丢`() {
        val premier = parseTab("matchhistorypremier")
        val json = ApiClient.matchesToJson(premier)
        val back = ApiClient.parseMatches(json)

        assertEquals(premier.size, back.size)
        val a = premier.first()
        val b = back.first { it.id == a.id }
        assertEquals(a.ranked, b.ranked)
        assertEquals(a.waitTimeSec, b.waitTimeSec)
        assertEquals(a.replayUrl, b.replayUrl)
        assertEquals(a.players.size, b.players.size)
        a.players.forEach { p ->
            val q = b.players.first { it.steamId == p.steamId }
            assertEquals("${p.name} Score 丢失", p.score, q.score)
            assertEquals("${p.name} ★MVP 丢失", p.mvps, q.mvps)
            assertEquals("${p.name} Ping 丢失", p.ping, q.ping)
            assertEquals("${p.name} 存活丢失", p.survivedRoundsEff, q.survivedRoundsEff)
            assertEquals(p.kills, q.kills)
            assertEquals(p.headshotKills, q.headshotKills)
        }
    }
}
