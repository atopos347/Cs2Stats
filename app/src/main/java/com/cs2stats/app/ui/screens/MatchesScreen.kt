package com.cs2stats.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cs2stats.app.data.demo.DemoParseState
import com.cs2stats.app.data.model.MatchResult
import com.cs2stats.app.ui.UiState
import com.cs2stats.app.ui.components.BatchParseCard
import com.cs2stats.app.ui.components.InfoBanner
import com.cs2stats.app.ui.components.MatchRow
import com.cs2stats.app.ui.components.SectionTitle

/**
 * 比赛列表：可按 胜 / 负 / 全部 过滤。
 *
 * 顶部另挂一块 [BatchParseCard]（批量解析 demo）：队列在跑时显示进度与「取消」，
 * 平时显示待解析场数与二次确认入口。
 */
@Composable
fun MatchesScreen(
    state: UiState.Ready,
    onOpenMatch: (String) -> Unit,
    demoState: DemoParseState? = null,
    networkType: () -> String = { "未知" },
    onStartQueue: () -> Unit = {},
    onCancelQueue: () -> Unit = {},
) {
    val matches = state.data.matches
    var filter by remember { mutableStateOf("all") }

    val shown = when (filter) {
        "win" -> matches.filter { it.result == MatchResult.WIN }
        "loss" -> matches.filter { it.result == MatchResult.LOSS }
        else -> matches
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == "all",
                onClick = { filter = "all" },
                label = { Text("全部 ${matches.size}") },
            )
            FilterChip(
                selected = filter == "win",
                onClick = { filter = "win" },
                label = { Text("胜利 ${matches.count { it.result == MatchResult.WIN }}") },
            )
            FilterChip(
                selected = filter == "loss",
                onClick = { filter = "loss" },
                label = { Text("失败 ${matches.count { it.result == MatchResult.LOSS }}") },
            )
        }

        Spacer(Modifier.height(10.dp))

        BatchParseCard(
            state = state,
            demoState = demoState,
            networkType = networkType,
            onStartQueue = onStartQueue,
            onCancelQueue = onCancelQueue,
        )

        if (shown.isEmpty()) {
            InfoBanner(
                if (matches.isEmpty()) "还没有比赛记录。到「设置」配置服务器地址，或等待同步。"
                else "当前筛选下没有比赛。"
            )
        } else {
            SectionTitle("${shown.size} 场比赛")
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(shown, key = { it.id }) { m ->
                    MatchRow(
                        match = m,
                        mySteamId = state.mySteamId,
                        onClick = { onOpenMatch(m.id) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
