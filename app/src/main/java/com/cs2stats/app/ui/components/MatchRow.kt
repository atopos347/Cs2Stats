package com.cs2stats.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.model.MatchResult
import com.cs2stats.app.data.rating.Rating3
import com.cs2stats.app.ui.theme.RatingHigh
import com.cs2stats.app.ui.theme.RatingLow
import com.cs2stats.app.ui.theme.RatingMid
import com.cs2stats.app.ui.theme.RatingStar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rating 数值 → 颜色（与 RatingChip 同一套口径）。 */
fun ratingColorOf(rating: Double): Color = when {
    rating >= 1.40 -> RatingStar
    rating >= 1.20 -> RatingHigh
    rating >= 1.00 -> RatingMid
    else -> RatingLow
}

private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/** 比赛列表行：结果色条 + 地图/比分 + 我的战绩 + Rating 胶囊。 */
@Composable
fun MatchRow(
    match: MatchDetail,
    mySteamId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val me = match.myLine(mySteamId)
    val rating = me?.let { Rating3.ratingOf(it) }
    val winColor = when (match.result) {
        MatchResult.WIN -> RatingHigh
        MatchResult.LOSS -> RatingLow
        MatchResult.TIE -> RatingMid
    }

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 结果条
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(winColor, RoundedCornerShape(4.dp)),
            )

            Column(Modifier.weight(1f)) {
                Text(
                    text = match.mapName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${dateFmt.format(Date(match.startedAt * 1000))} · ${match.mode}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (me != null) {
                    Text(
                        text = "K/D/A ${me.kills}/${me.deaths}/${me.assists} · HS " +
                            "%.0f%%".format(me.headshotRate * 100) +
                            (if (me.damage > 0) " · ADR %.0f".format(me.adr) else "") +
                            (if ((me.mvps ?: 0) > 0) " · ★${me.mvps}" else "") +
                            (if (me.score != null) " · ${me.score} 分" else ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${match.myScore} : ${match.enemyScore}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = winColor,
                )
                Text(
                    text = when (match.result) {
                        MatchResult.WIN -> "胜利"
                        MatchResult.LOSS -> "失败"
                        MatchResult.TIE -> "平局"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (rating != null) RatingChip(rating)
        }
    }
}
