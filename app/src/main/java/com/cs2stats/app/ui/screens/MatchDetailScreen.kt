package com.cs2stats.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cs2stats.app.data.demo.DemoNative
import com.cs2stats.app.data.demo.DemoParseState
import com.cs2stats.app.data.demo.DemoPhase
import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.model.MatchResult
import com.cs2stats.app.data.model.PlayerLine
import com.cs2stats.app.data.model.Team
import com.cs2stats.app.data.rating.Rating3
import com.cs2stats.app.data.rating.asPercent
import com.cs2stats.app.ui.UiState
import com.cs2stats.app.ui.components.InfoBanner
import com.cs2stats.app.ui.components.RatingChip
import com.cs2stats.app.ui.components.SectionTitle
import com.cs2stats.app.ui.components.ratingColorOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 比赛详情：比分 + 我的 Rating 3.0 六子项拆解 + 本机 demo 解析入口 + 双方战绩表。
 *
 * [demoState] 是全局解析状态（`DemoParseBus`）：任务跑在前台服务里，
 * 退出本页、旋转屏幕都不会中断，回来时进度照样显示。
 */
@Composable
fun MatchDetailScreen(
    state: UiState.Ready,
    matchId: String,
    demoState: DemoParseState?,
    onStartParse: (MatchDetail) -> Unit,
    onCancelParse: () -> Unit,
) {
    val match = state.data.matches.firstOrNull { it.id == matchId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(2.dp))

        if (match == null) {
            InfoBanner("找不到这场比赛（可能已被服务器清理）。", isError = true)
        } else {
            MatchHeader(match)

            val me = match.myLine(state.mySteamId)
            if (me == null) {
                InfoBanner("这场比赛里没有匹配到你的战绩行（检查设置里的 SteamID64）。")
            } else {
                MyPerformance(me, hasEconomy = match.hasRoundEconomy)
            }

            DemoParseCard(
                match = match,
                demoState = demoState,
                onStart = onStartParse,
                onCancel = onCancelParse,
            )

            SectionTitle("双方战绩")
            PlayerTable("我方", match.players.filter { it.team == match.myTeam })
            PlayerTable(
                "对方",
                match.players.filter { it.team != match.myTeam },
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MatchHeader(match: MatchDetail) {
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val winColor = when (match.result) {
        MatchResult.WIN -> ratingColorOf(1.30)
        MatchResult.LOSS -> ratingColorOf(0.70)
        MatchResult.TIE -> ratingColorOf(1.05)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = match.mapName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${match.mode} · ${dateFmt.format(Date(match.startedAt * 1000))}" +
                    (if (match.durationSec > 0) " · ${match.durationSec / 60} 分钟" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (match.ranked != null || match.waitTimeSec != null) {
                Text(
                    text = listOfNotNull(
                        match.ranked?.let { if (it) "排位" else "非排位" },
                        match.waitTimeSec?.let { "等待 ${it / 60}分${"%02d".format(it % 60)}秒" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${match.myScore}  :  ${match.enemyScore}",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = winColor,
            )
            Text(
                text = when (match.result) {
                    MatchResult.WIN -> "胜 利"
                    MatchResult.LOSS -> "失 败"
                    MatchResult.TIE -> "平 局"
                },
                style = MaterialTheme.typography.labelLarge,
                color = winColor,
            )
        }
    }
}

/** 我的表现卡：总分 + 六个子评分。 */
@Composable
private fun MyPerformance(me: PlayerLine, hasEconomy: Boolean) {
    val breakdown = Rating3.rate(me)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "我的 Rating 3.0",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (breakdown.isPartial)
                            "部分维度数据缺失，为降级近似"
                        else
                            "六子项齐全 · 结构对齐 HLTV 3.0",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "%.2f".format(breakdown.overall),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = ratingColorOf(breakdown.overall),
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("K/D/A", "${me.kills}/${me.deaths}/${me.assists}", Modifier.weight(1f))
                MiniStat("爆头率", asPercent(breakdown.hsRate), Modifier.weight(1f))
                MiniStat(
                    "存活率",
                    me.survivalRate?.let {
                        (if (me.survivalDerived) "≈" else "") + asPercent(it)
                    } ?: "—",
                    Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat(
                    "ADR",
                    if (me.damage > 0) "%.0f".format(me.adr) else "—",
                    Modifier.weight(1f),
                )
                MiniStat(
                    "KAST",
                    if (me.kastRounds != null && me.roundsPlayed > 0)
                        asPercent(me.kastRounds.toDouble() / me.roundsPlayed)
                    else "—",
                    Modifier.weight(1f),
                )
                MiniStat("★ MVP", me.mvps?.toString() ?: "—", Modifier.weight(1f))
            }

            Spacer(Modifier.height(14.dp))

            breakdown.subs.forEach { sub ->
                SubRatingRow(sub)
                Spacer(Modifier.height(10.dp))
            }

            FieldNote(breakdown = breakdown, hasEconomy = hasEconomy)
        }
    }
}

/**
 * 数据字段说明：把「页面给了什么 / 反推了什么 / 页面压根没有什么」写清楚，
 * 避免把降级近似误当成完整 Rating。
 */
@Composable
private fun FieldNote(breakdown: Rating3.Breakdown, hasEconomy: Boolean) {
    val missing = breakdown.subs.filter { !it.available }.joinToString("、") { it.rating.labelZh }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "字段来源：Steam 官匹战绩页 —— K/A/D、爆头率、★MVP、Score、Ping、" +
                "时长/等待/排位/demo 链接；存活率按「回合 − 阵亡」恒等反推（标 ≈）。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (missing.isEmpty()) {
                if (hasEconomy) "六子项齐全，含回合级经济：Round Swing 走真实口径。"
                else "六子项齐全（结构对齐 HLTV 3.0）。"
            } else {
                "页面不含伤害/ADR、KAST、多杀、开局杀${
                    if (hasEconomy) "" else "、回合经济"
                }，故「$missing」缺席，已从权重中剔除并重新归一化 —— 属**降级近似**，" +
                    "补齐需 demo 解析或第三方数据源（设置页预留 FACEIT API）。"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (missing.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp),
            )
            .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SubRatingRow(sub: Rating3.SubScore) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sub.rating.labelZh,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = sub.rawLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (sub.available) "%.2f".format(sub.value) else "—",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (sub.available) ratingColorOf(sub.value)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (sub.available) (sub.value / 2.0).coerceIn(0.0, 1.0).toFloat() else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp),
            color = if (sub.available) ratingColorOf(sub.value)
            else MaterialTheme.colorScheme.outline,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/**
 * 本机 Demo 解析区。
 *
 * 四种形态互斥，**拿不准就如实说、不给假按钮**：
 *
 * 1. 已解析（`demoParsedAt` 非空）→ 打勾徽标 + 说明补了什么、没动什么；
 * 2. 任务进行中 → 阶段 + 进度条 + 取消（任务在前台服务里，退出本页照样跑）；
 * 3. 不可解析 → 链接已过期（Valve 只留约 30 天）/ 机型不支持，直接说明；
 * 4. 可解析 → 下载按钮，副文案写清体积、耗时与**合并铁律**。
 */
@Composable
private fun DemoParseCard(
    match: MatchDetail,
    demoState: DemoParseState?,
    onStart: (MatchDetail) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    // Android 13+ 通知运行时权限：没给也不阻断解析，只是通知栏不显示进度（页面内有进度条）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val s = demoState
    val forThis = s != null && s.matchId == match.id
    val running = forThis && s.phase in RUNNING_PHASES

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "本机 Demo 解析",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (match.demoParsedAt != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "已补齐",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            when {
                // ① 正在跑：跨页面存活，退出也照常推进
                running && s != null -> {
                    Text(
                        text = when (s.phase) {
                            DemoPhase.DOWNLOADING -> "① 下载回放"
                            DemoPhase.PARSING -> "② 本机解析（demoinfocs）"
                            else -> "③ 合并并归档网盘"
                        } + "  ${(s.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = s.message ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("取消解析")
                    }
                }

                // ② 已解析：只说明结果，字段口径写死在文案里
                match.demoParsedAt != null -> {
                    Text(
                        text = "已由本机 demo 补齐：伤害/ADR、KAST、多杀、开局杀、存活。" +
                            "K/A/D、爆头、★MVP、Score 仍以战绩页为准（不覆盖）。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    match.demoParsedAt?.let {
                        Text(
                            text = "完成于 ${dateFmt.format(Date(it))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                // ③ 不可解析：如实说明，不摆按钮
                !DemoNative.available -> {
                    Text(
                        text = "当前机型不支持本地解析（解析库只随 arm64-v8a 打包）。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                match.replayUrl.isNullOrBlank() -> {
                    Text(
                        text = "这场比赛的 Demo 已过期（Valve 只保留约 30 天），无法补解析；" +
                            "当前数据为战绩页口径，缺失字段保持为 —。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ④ 可解析
                else -> {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            onStart(match)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("下载并解析本局 Demo")
                    }
                    Text(
                        text = "回放约 142 MB，下载 + 解析约 1~3 分钟，可退出页面或锁屏（前台服务继续）。" +
                            "解析**只补空缺**：战绩页已有的 K/A/D、爆头、★MVP、Score 一律不改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (forThis && s?.phase == DemoPhase.CANCELLED) {
                        Text(
                            text = "上次解析已取消。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            if (forThis && s?.phase == DemoPhase.FAILED) {
                InfoBanner("解析失败：${s.message ?: "未知错误"}", isError = true)
            }
        }
    }
}

/** 任务进行中的阶段（详情页据此显示进度 + 取消按钮）。 */
private val RUNNING_PHASES = setOf(
    DemoPhase.DOWNLOADING,
    DemoPhase.PARSING,
    DemoPhase.SAVING,
)

@Composable
private fun PlayerTable(title: String, players: List<PlayerLine>) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )

            players.sortedByDescending { it.kills }.forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(22.dp)
                            .background(
                                if (p.team == Team.CT || p.team == Team.TEAM_A)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary,
                                RoundedCornerShape(4.dp),
                            ),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = buildString {
                                append("${p.kills}杀 ${p.deaths}死 ${p.assists}助")
                                if (p.kills > 0) append(" · HS ").append(asPercent(p.headshotRate))
                                if (p.damage > 0) append(" · ADR %.0f".format(p.adr))
                                p.mvps?.takeIf { it > 0 }?.let { append(" · ★").append(it) }
                                p.score?.let { append(" · ").append(it).append(" 分") }
                                p.ping?.let { append(" · ").append(it).append("ms") }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RatingChip(Rating3.ratingOf(p))
                }
            }
        }
    }
}
