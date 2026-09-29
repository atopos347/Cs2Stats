package com.cs2stats.app.data.rating

import com.cs2stats.app.data.model.PlayerLine
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * HLTV **Rating 3.0 近似**实现。
 *
 * 背景（已核对 HLTV 官方文章《Introducing Rating 3.0》《Rating 3.0 adjustments go live》）：
 *
 * - Rating 3.0 由 **六个子评分** 组成：Kills、Damage、Survival、KAST、Multi-Kills、Round Swing；
 * - 整体遵循 **产出 : 代价 = 60 : 40** 的平衡——产出侧是 Kills / Damage / Multi-Kills，
 *   代价侧是 KAST / Survival，Round Swing 兼具两者的性质（各占一半）；
 * - 六个子评分在赛事维度上的均值为 **1.00**；
 * - Kill / Damage / Survival / KAST / Multi-Kills 按「经济优劣势」做 eco 修正，
 *   Round Swing 依赖回合级经济与击杀造成的胜率变化。
 *
 * HLTV **未公开精确系数**，eco 修正与 Round Swing 需要逐回合经济快照。因此本实现是
 * 「结构一致、系数自定标定」的近似：
 *
 * 1. 子评分 = 实测值 / 职业赛场基准值（基准量级取自近年赛事均值），
 *    使平均水平天然落在 1.00 附近，与 HLTV「均值 1.00」的口径对齐；
 * 2. 产出侧 0.60 与代价侧 0.40 **各自在侧内归一化**，再按 60/40 合成；
 * 3. 缺失子评分（Steam 官匹缺 KAST / 伤害时）直接从权重中剔除并重新归一化，
 *    评分仍可用，UI 以 [Breakdown.isPartial] 标注「近似度」；
 *    - 官匹实际可得：**Kills + Survival**（生存按恒等式
 *      `存活 = 回合 − 阵亡` 反推，[PlayerLine.survivedRoundsEff]，UI 标 `≈`），
 *      因此 **60:40 的产出/代价结构仍然成立**，而不是退化成单看击杀；
 *    - 官匹确实拿不到：Damage、KAST、Multi-Kills、Round Swing 四项，逐回合数据
 *      Valve 的 Match Stats / Match Events 页签实测返回空，只能由 demo 解析或
 *      第三方数据源补齐（设置页已预留 FACEIT API 字段）；
 * 4. Round Swing 先用「开局杀净胜 / 回合」近似；后端补齐逐回合经济数据后，
 *    只需替换 [swing]，其余管线不动。
 */
object Rating3 {

    /** 产出侧总权重（HLTV：60%）。 */
    private const val OUTPUT_SHARE = 0.60
    /** 代价侧总权重（HLTV：40%）。 */
    private const val COST_SHARE = 0.40

    private enum class Side { OUT, COST }

    /**
     * 权重表。侧内相对权重决定该子项在本侧的占比（合成时侧内会归一化，
     * 所以绝对数值只在同侧内有意义）。
     */
    private data class Part(val rating: SubRating, val side: Side, val w: Double)

    private val parts: List<Part> = listOf(
        Part(SubRating.KILLS, Side.OUT, 0.56),
        Part(SubRating.DAMAGE, Side.OUT, 0.24),
        Part(SubRating.MULTIKILL, Side.OUT, 0.12),
        Part(SubRating.SWING, Side.OUT, 0.04),   // swing 的一半在产出侧
        Part(SubRating.KAST, Side.COST, 0.70),
        Part(SubRating.SURVIVAL, Side.COST, 0.30),
        Part(SubRating.SWING, Side.COST, 0.04),  // 另一半在代价侧
    )

    // ---- 职业赛场基准值（把实测值标定到 1.00 = 平均水平） ----
    private const val KPR_BASE = 0.68        // 击杀/回合
    private const val ADR_BASE = 76.0        // 回合伤害
    private const val KAST_BASE = 0.72       // KAST 回合占比
    private const val SURVIVAL_BASE = 0.30   // 存活回合占比
    private const val MULTI_KILL_BASE = 0.16 // ≥2 杀 回合占比
    private const val SWING_BASE = 0.030     // 开局杀净胜/回合（顶级选手约 +0.03~0.04）

    private const val MIN_SUB = 0.10
    private const val MAX_SUB = 2.50

    enum class SubRating(val labelZh: String) {
        KILLS("击杀"),
        DAMAGE("伤害"),
        MULTIKILL("多杀"),
        SWING("回合影响力"),
        KAST("KAST"),
        SURVIVAL("生存"),
    }

    /** 单个子评分：得分 + 原始实测值的可读形式，用于详情页展示。 */
    data class SubScore(
        val rating: SubRating,
        val value: Double,     // 已标定：1.00 = 平均水平
        val rawLabel: String,  // 如 "0.71 KPR"
        val available: Boolean,
    )

    data class Breakdown(
        val overall: Double,
        val subs: List<SubScore>,
        val isPartial: Boolean, // true = 有子项缺失，属降级近似
        val hsRate: Double,
    )

    /** 计算单场单人的 Rating 3.0 近似值。 */
    fun rate(line: PlayerLine): Breakdown {
        val rounds = line.roundsPlayed
        val kpr = if (rounds > 0) line.kills.toDouble() / rounds else 0.0
        val adr = if (rounds > 0) line.damage.toDouble() / rounds else 0.0
        val hasDamage = line.damage > 0 && rounds > 0

        val mk = line.multiKillRounds
        val mkRate = if (mk != null && rounds > 0) mk.toDouble() / rounds else null

        val kast = line.kastRounds
        val kastRate = if (kast != null && rounds > 0) kast.toDouble() / rounds else null

        val surv = line.survivedRoundsEff
        val survRate = if (surv != null && rounds > 0) surv.toDouble() / rounds else null
        val survLabelPrefix = if (line.survivalDerived) "≈" else ""

        val swingVal = swing(line)

        val subs = listOf(
            sub(
                SubRating.KILLS,
                kpr / KPR_BASE, rounds > 0,
                if (rounds > 0) fmt(kpr) + " KPR" else "—"
            ),
            sub(
                SubRating.DAMAGE,
                adr / ADR_BASE, hasDamage,
                if (hasDamage) fmt(adr) + " ADR" else "—"
            ),
            sub(
                SubRating.MULTIKILL,
                (mkRate ?: 0.0) / MULTI_KILL_BASE, mkRate != null,
                mkRate?.let { fmt(it * 100) + "% 回合" } ?: "—"
            ),
            sub(
                SubRating.SWING,
                (swingVal ?: 0.0) / SWING_BASE, swingVal != null,
                swingRawLabel(line, swingVal)
            ),
            sub(
                SubRating.KAST,
                (kastRate ?: 0.0) / KAST_BASE, kastRate != null,
                kastRate?.let { fmt(it * 100) + "% 回合" } ?: "—"
            ),
            sub(
                SubRating.SURVIVAL,
                (survRate ?: 0.0) / SURVIVAL_BASE, survRate != null,
                survRate?.let { survLabelPrefix + fmt(it * 100) + "% 回合" } ?: "—"
            ),
        )

        return Breakdown(
            overall = combine(subs),
            subs = subs,
            isPartial = subs.any { !it.available },
            hsRate = if (line.kills > 0) line.headshotKills.toDouble() / line.kills else 0.0,
        )
    }

    /** 只要结果时的快捷入口。 */
    fun ratingOf(line: PlayerLine): Double = rate(line).overall

    // ---------- 内部 ----------

    private fun sub(r: SubRating, value: Double, available: Boolean, rawLabel: String) =
        SubScore(r, if (available) clamp(value) else 0.0, rawLabel, available)

    private fun combine(subs: List<SubScore>): Double {
        val available = subs.filter { it.available }.associateBy { it.rating }

        fun side(side: Side): Pair<Double, Double>? {
            val entries = parts.filter { it.side == side && available.containsKey(it.rating) }
            if (entries.isEmpty()) return null
            val wSum = entries.sumOf { it.w }
            if (wSum <= 0.0) return null
            val vSum = entries.sumOf { p -> available.getValue(p.rating).value * p.w }
            return vSum / wSum to wSum
        }

        val output = side(Side.OUT)?.first
        val cost = side(Side.COST)?.first

        return when {
            output != null && cost != null -> clamp(OUTPUT_SHARE * output + COST_SHARE * cost)
            output != null -> clamp(output)
            cost != null -> clamp(cost)
            else -> 1.00
        }
    }

    private fun clamp(v: Double): Double = v.coerceIn(MIN_SUB, MAX_SUB)

    /**
     * 近似 Round Swing：开局杀净胜 / 回合（HLTV 真实实现需逐回合经济快照与胜率变化）。
     * 返回 null 表示数据缺失，该子项将被剔除并重新归一化权重。
     */
    private fun swing(line: PlayerLine): Double? {
        val ok = line.openingKills != null && line.openingDeaths != null && line.roundsPlayed > 0
        if (!ok) return null
        val diff = (line.openingKills ?: 0) - (line.openingDeaths ?: 0)
        return diff.toDouble() / line.roundsPlayed
    }

    private fun swingRawLabel(line: PlayerLine, swingVal: Double?): String =
        if (swingVal != null) "开局 ${line.openingKills}/${line.openingDeaths}" else "—"

    private fun fmt(d: Double) = "%.2f".format(d)

    /** 权重表的可读形式，便于在设置页核对计算口径。 */
    fun describeWeights(): String =
        parts.joinToString("\n") { "${it.rating.labelZh}(${it.side}): ${"%.2f".format(it.w)}" }
}

// ---------- 统计小工具 ----------

/** 批量算 Rating。 */
fun List<PlayerLine>.ratings(): List<Double> = map { Rating3.ratingOf(it) }

/** 均值（空列表返回 0）。 */
fun List<Double>.mean(): Double = if (isEmpty()) 0.0 else sum() / size

/** 保留 n 位小数。 */
fun Double.round(n: Int): Double {
    val p = 10.0.pow(n)
    return (this * p).roundToLong() / p
}

/** 格式化为百分比字符串，如 0.4523 -> "45.2%"。 */
fun asPercent(value: Double): String = "%.1f%%".format(value * 100)
