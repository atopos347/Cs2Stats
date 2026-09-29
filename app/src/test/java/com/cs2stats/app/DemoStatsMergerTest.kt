package com.cs2stats.app

import com.cs2stats.app.data.collect.GcpdParser
import com.cs2stats.app.data.demo.DemoMergeException
import com.cs2stats.app.data.demo.DemoStatsMerger
import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.rating.Rating3
import com.cs2stats.app.data.remote.ApiClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 用**真机 demo 的解析产物**做回归（`src/test/resources/demo/parse_result.json`
 * = PC 端 demoinfocs 跑 `003845258388027998412_0649725551.dem.bz2` 的结果，
 * 对应战绩页 2026-09-28 08:52 远古遗迹 13:11 那场，已与网页记分板逐项对过账）。
 *
 * 锁死三条铁律：
 *
 * 1. **只补空缺**：K/A/D/爆头/★/Score/Ping 一个都不许动；
 * 2. **对账不过整份丢弃**：K/D 与战绩页一致的人数不足 60% 就报错，绝不写入；
 * 3. **不编造**：`hasRoundEconomy` 不能因为 demo 里有经济数据就变 true
 *    （本应用不落库回合级经济，UI 一旦信了就会声称「Round Swing 走真实口径」）。
 */
class DemoStatsMergerTest {

    private val mySteamId = "76561199517803315"

    private fun res(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "缺少测试资源 $name" }
            .bufferedReader().readText()

    /** 样本 demo 对应的那一场（用 replay 直链精确定位，不依赖排序）。 */
    private fun target(): MatchDetail {
        val html = res("gcpd/gcpd_matchhistorypremier_full.html")
        val all = GcpdParser.parse(html, "matchhistorypremier", mySteamId, "Atopos").matches
        return all.firstOrNull {
            it.replayUrl?.endsWith("003845258388027998412_0649725551.dem.bz2") == true
        } ?: error("样本里找不到带该 replay 直链的比赛（gcpd 样本是否被换过？）")
    }

    private fun fixture(): String = res("demo/parse_result.json")

    @Test
    fun `合并后六个子项齐全而战绩页字段一字不改`() {
        val m = target()
        val before = m.myLine(mySteamId)!!
        assertEquals("样本对不上", 21, before.kills)
        assertTrue("页面口径本该是降级近似", Rating3.rate(before).isPartial)

        val out = DemoStatsMerger.merge(m, fixture(), now = 1_700_000_000_000L)
        val after = out.match.myLine(mySteamId)!!

        // 只补空缺：demo 六列
        assertEquals(2477, after.damage ?: -1)
        assertEquals(16, after.kastRounds ?: -1)
        assertEquals(6, after.survivedRounds ?: -1)
        assertEquals(8, after.multiKillRounds ?: -1)
        assertEquals(3, after.openingKills ?: -1)
        assertEquals(4, after.openingDeaths ?: -1)

        // 战绩页原样（demo 也给了 K/D，但绝不能覆盖）
        assertEquals(before.kills, after.kills)
        assertEquals(before.deaths, after.deaths)
        assertEquals(before.assists, after.assists)
        assertEquals(before.headshotKills, after.headshotKills)
        assertEquals(before.score, after.score)
        assertEquals(before.mvps, after.mvps)
        assertEquals(before.ping, after.ping)
        assertEquals(before.roundsPlayed, after.roundsPlayed)
        assertEquals(before.steamId, after.steamId)

        assertEquals(1_700_000_000_000L, out.match.demoParsedAt ?: -1L)

        val b = Rating3.rate(after)
        assertFalse("补完字段后不该再标降级", b.isPartial)
        assertEquals(6, b.subs.count { it.available })
        assertTrue("Rating 越界 ${b.overall}", b.overall in 0.10..2.50)
        // 存活由 demo 直接给出，不再走「回合 − 阵亡」反推 => 标签不该有 ≈
        assertFalse(b.subs.first { it.rating == Rating3.SubRating.SURVIVAL }.rawLabel.startsWith("≈"))
    }

    @Test
    fun `十名玩家全部补齐且人数不变`() {
        val m = target()
        assertEquals(10, m.players.size)

        val out = DemoStatsMerger.merge(m, fixture(), now = 0)
        assertEquals(10, out.match.players.size)
        for (p in out.match.players) {
            assertTrue("${p.name} damage 没补上", p.damage > 0)
            assertTrue("${p.name} kast 没补上", p.kastRounds != null)
            assertTrue("${p.name} multi 没补上", p.multiKillRounds != null)
            assertTrue("${p.name} opening 没补上", p.openingKills != null && p.openingDeaths != null)
            assertTrue("${p.name} 存活越界", (p.survivedRounds ?: -1) in 0..p.roundsPlayed)
            assertTrue("${p.name} KAST 越界", (p.kastRounds ?: 999) <= p.roundsPlayed)
        }
        assertTrue(out.note.contains("demoinfocs"))
    }

    @Test
    fun `战绩页已有值绝不被覆盖`() {
        val m = target().let { base ->
            base.copy(
                players = base.players.map {
                    if (it.steamId == mySteamId)
                        it.copy(damage = 999, kastRounds = 1, openingKills = 7, survivedRounds = 2)
                    else it
                },
            )
        }

        val p = DemoStatsMerger.merge(m, fixture(), now = 0).match.myLine(mySteamId)!!
        assertEquals(999, p.damage)
        assertEquals(1, p.kastRounds ?: -1)
        assertEquals(7, p.openingKills ?: -1)
        assertEquals(2, p.survivedRounds ?: -1)
    }

    @Test
    fun `K对不上一半以上就整份丢弃`() {
        val m = target()
        val tampered = JSONObject(fixture())
        val arr = tampered.getJSONArray("players")
        // 5/10 人 K 被改到差 50 —— 一致人数 5 < 需要的 6，必须整份拒绝
        for (i in 0 until 5) {
            arr.getJSONObject(i).put("kills", arr.getJSONObject(i).getInt("kills") + 50)
        }

        try {
            DemoStatsMerger.merge(m, tampered.toString(), now = 0)
            fail("对账不过却写入了")
        } catch (e: DemoMergeException) {
            assertTrue("错误信息应说明原因：${e.message}", e.message!!.contains("对不上"))
        }
    }

    @Test
    fun `解析器报错时原样透传`() {
        try {
            DemoStatsMerger.merge(target(), """{"version":1,"error":"已取消"}""", now = 0)
            fail("error 字段没被识别")
        } catch (e: DemoMergeException) {
            assertEquals("已取消", e.message)
        }
    }

    @Test
    fun `回合数太少的残缺结果拒收`() {
        val json = """{"version":1,"players":[],"rounds":[{"num":1}]}"""
        try {
            DemoStatsMerger.merge(target(), json, now = 0)
            fail("1 个回合也写进去了")
        } catch (e: DemoMergeException) {
            assertTrue("信息：${e.message}", e.message!!.contains("回合"))
        }
    }

    @Test
    fun `战绩页没有的替换上场玩家按 demo 追加`() {
        val m = target()
        val absent = "monkeymilk"
        assertTrue("前置：战绩页本该有这行", m.players.any { it.name == absent })
        // 模拟「页面那行没解析出来 / 中途替换上场」：拿掉一行再合并
        val trimmed = m.copy(players = m.players.filterNot { it.name == absent })
        assertEquals(9, trimmed.players.size)

        val out = DemoStatsMerger.merge(trimmed, fixture(), now = 0)
        assertEquals(10, out.match.players.size)
        val extra = out.match.players.first { it.name == absent }
        assertEquals(25, extra.kills)
        assertEquals(16, extra.deaths)
        assertEquals(3170, extra.damage)
        assertEquals(16, extra.kastRounds ?: -1)
        assertEquals(8, extra.survivedRounds ?: -1)
        assertTrue("追加的玩家该带队伍", extra.team.name == "CT" || extra.team.name == "T")
        assertTrue("说明里该提到追加：${out.note}", out.note.contains("追加"))
        // 追加行属于 demo 来源：回合数与总回合一致
        assertEquals(24, extra.roundsPlayed)
    }

    @Test
    fun `归档往返后补齐字段与时间戳都不丢`() {
        val out = DemoStatsMerger.merge(target(), fixture(), now = 123L)
        val back = ApiClient.parseMatches(ApiClient.matchesToJson(listOf(out.match))).single()

        assertEquals(123L, back.demoParsedAt ?: -1L)
        val p = back.myLine(mySteamId)!!
        assertEquals(2477, p.damage)
        assertEquals(16, p.kastRounds ?: -1)
        assertEquals(6, p.survivedRounds ?: -1)
        assertEquals(8, p.multiKillRounds ?: -1)
        assertEquals(3, p.openingKills ?: -1)
        assertEquals(4, p.openingDeaths ?: -1)
        assertEquals(21, p.kills)
        assertEquals(53, p.score ?: -1)
    }

    @Test
    fun `回合级经济不落库就不能声称有`() {
        // demo 里确实带 ctBuy/tBuy，但 MatchDetail 不存回合列表；
        // 一旦把 hasRoundEconomy 置真，详情页就会宣称「Round Swing 走真实口径」——
        // 那是编造。这里锁死它必须保持 false。
        val json = JSONObject(fixture())
        assertTrue("前置：demo 确实带经济", json.getBoolean("hasEconomy"))

        val out = DemoStatsMerger.merge(target(), fixture(), now = 0)
        assertFalse("demo 有经济 ≠ 本应用有回合级经济", out.match.hasRoundEconomy)
    }

    // ---------- 抓取覆盖保护（carryDemoFields） ----------

    @Test
    fun `重新抓取时搬运 demo 成果而页面列一个不动`() {
        val parsed = DemoStatsMerger.merge(target(), fixture(), now = 111L).match
        // 模拟「从 Steam 抓取」拿到的原始页面记录：demo 那 6 列与时间戳全空
        val page = parsed.copy(
            demoParsedAt = null,
            players = parsed.players.map {
                it.copy(
                    damage = 0, kastRounds = null, survivedRounds = null,
                    multiKillRounds = null, openingKills = null, openingDeaths = null,
                )
            },
        )

        val carried = DemoStatsMerger.carryDemoFields(page, parsed)
        val me = carried.myLine(mySteamId)!!
        assertEquals(2477, me.damage)
        assertEquals(16, me.kastRounds ?: -1)
        assertEquals(6, me.survivedRounds ?: -1)
        assertEquals(8, me.multiKillRounds ?: -1)
        assertEquals(3, me.openingKills ?: -1)
        assertEquals(4, me.openingDeaths ?: -1)
        assertEquals(111L, carried.demoParsedAt ?: -1L)

        // 页面列必须还是「新抓的」这一份（抓取是页面数据的权威来源）
        assertEquals(page.players.size, carried.players.size)
        for ((i, p) in page.players.withIndex()) {
            val c = carried.players[i]
            assertEquals("K", p.kills, c.kills)
            assertEquals("D", p.deaths, c.deaths)
            assertEquals("A", p.assists, c.assists)
            assertEquals("HS", p.headshotKills, c.headshotKills)
            assertEquals("Score", p.score, c.score)
            assertEquals("★", p.mvps, c.mvps)
            assertEquals("Ping", p.ping, c.ping)
            assertEquals("队伍", p.team, c.team)
        }
    }

    @Test
    fun `没见过的比赛原样返回而没解析过的不长字段`() {
        val page = target()
        assertSame("没见过的比赛该原样返回", page, DemoStatsMerger.carryDemoFields(page, null))

        val carried = DemoStatsMerger.carryDemoFields(page, page)   // 见过但从未解析
        assertEquals(page.players.size, carried.players.size)
        assertNull("不能凭空冒出时间戳", carried.demoParsedAt)
        for (p in carried.players) {
            assertEquals(0, p.damage)
            assertNull(p.kastRounds)
            assertNull(p.survivedRounds)
            assertNull(p.multiKillRounds)
        }
    }

    @Test
    fun `页面缺行时 demo 追加的替换上场玩家不丢`() {
        val full = target()
        val page = full.copy(players = full.players.filterNot { it.name == "monkeymilk" })
        assertEquals(9, page.players.size)
        val parsed = DemoStatsMerger.merge(page, fixture(), now = 7L).match
        assertEquals(10, parsed.players.size)

        val carried = DemoStatsMerger.carryDemoFields(page, parsed)
        assertEquals("追加的那行该被保住", 10, carried.players.size)
        val extra = carried.players.first { it.name == "monkeymilk" }
        assertEquals(3170, extra.damage)
        assertEquals(16, extra.kastRounds ?: -1)
        assertEquals(7L, carried.demoParsedAt ?: -1L)
    }
}
