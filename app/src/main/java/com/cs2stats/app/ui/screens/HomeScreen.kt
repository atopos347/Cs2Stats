package com.cs2stats.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cs2stats.app.data.model.MatchResult
import com.cs2stats.app.data.rating.Rating3
import com.cs2stats.app.data.rating.asPercent
import com.cs2stats.app.data.rating.round
import com.cs2stats.app.ui.UiState
import com.cs2stats.app.ui.components.InfoBanner
import com.cs2stats.app.ui.components.MatchRow
import com.cs2stats.app.ui.components.SectionTitle
import com.cs2stats.app.ui.components.StatCard
import com.cs2stats.app.ui.components.ratingColorOf

/**
 * 总览：总场均 Rating 3.0、总爆头率、K/D、KAST、ADR、胜率 + 近期走势 + 最近比赛。
 */
@Composable
fun HomeScreen(
    state: UiState.Ready,
    onOpenMatch: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val overall = state.overall
    val matches = state.data.matches

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(2.dp))

        // 数据源 + 错误提示
        InfoBanner(
            text = buildString {
                append("数据来源：${state.data.sourceLabel}")
                state.data.error?.let { append("\n⚠ ").append(it) }
            },
            isError = state.data.error != null,
        )

        // 核心指标 2 行
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "总场均 Rating 3.0",
                value = "%.2f".format(overall.avgRating),
                modifier = Modifier.weight(1f),
                sub = "共 ${overall.matchCount} 场 · HLTV 近似",
                valueColor = ratingColorOf(overall.avgRating),
            )
            StatCard(
                label = "总爆头率",
                value = asPercent(overall.avgHeadshotRate),
                modifier = Modifier.weight(1f),
                sub = "按总击杀加权",
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "K / D",
                value = "%.2f".format(overall.kdRatio),
                modifier = Modifier.weight(1f),
                sub = "总击杀 / 总死亡",
            )
            StatCard(
                label = "KAST",
                value = if (overall.kastRate > 0) asPercent(overall.kastRate) else "—",
                modifier = Modifier.weight(1f),
                sub = if (overall.kastRate > 0) "有数据的场次" else "数据源未提供",
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "ADR",
                value = if (overall.avgAdr > 0) "%.0f".format(overall.avgAdr) else "—",
                modifier = Modifier.weight(1f),
                sub = if (overall.avgAdr > 0) "场均回合伤害" else "数据源未提供",
            )
            StatCard(
                label = "胜率",
                value = asPercent(overall.winRate),
                modifier = Modifier.weight(1f),
                sub = "${overall.winCount} 胜 ${overall.matchCount - overall.winCount} 负",
            )
        }

        // 近期走势
        SectionTitle("近期 Rating 走势")
        RatingTrendChart(
            ratings = matches.take(12).mapNotNull { m ->
                m.myLine(state.mySteamId)?.let { Rating3.ratingOf(it) }
            },
        )

        // 最近比赛
        SectionTitle(
            title = "最近比赛",
            modifier = Modifier.clickable(onClick = onSeeAll),
            actionText = "全部比赛 ›",
        )
        if (matches.isEmpty()) {
            InfoBanner("还没有比赛记录。配置服务器地址或等待同步。")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.take(5).forEach { m ->
                    MatchRow(match = m, mySteamId = state.mySteamId, onClick = { onOpenMatch(m.id) })
                }
            }
        }

        // 胜负分布小结
        val wins = matches.count { it.result == MatchResult.WIN }
        val losses = matches.count { it.result == MatchResult.LOSS }
        val ties = matches.count { it.result == MatchResult.TIE }
        Text(
            text = "胜负：$wins 胜 / $losses 负${if (ties > 0) " / $ties 平" else ""}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

/**
 * 近期 Rating 柱状图：每根柱子高度 = rating / 2.0（上限 2.0），
 * 柱顶标注数值，颜色随分数段变化。
 */
@Composable
private fun RatingTrendChart(ratings: List<Double>) {
    if (ratings.isEmpty()) {
        InfoBanner("暂无可用于绘制走势的比赛。")
        return
    }
    val columnHeight = 132.dp
    val barMax = 100.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(columnHeight),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ratings.forEach { r ->
            val frac = (r / 2.0).coerceIn(0.06, 1.0)
            val color = ratingColorOf(r)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(columnHeight),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = r.round(2).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barMax * frac.toFloat())
                        .background(
                            color.copy(alpha = 0.85f),
                            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                        ),
                )
            }
        }
    }
}
