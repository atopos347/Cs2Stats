package com.cs2stats.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cs2stats.app.data.demo.DemoParseState
import com.cs2stats.app.data.demo.DemoQueue
import com.cs2stats.app.ui.UiState

/**
 * 比赛列表页的「批量解析 demo」卡片。
 *
 * 两种形态：
 *
 * - **队列在跑**：显示队列进度（文案由服务带「第 i/N 场 ·」前缀）+「取消队列」；
 * - **平时**：显示待解析场数 + 按钮，点开**二次确认**（列出场次、流量估计，
 *   并把当前网络类型念出来——非 Wi-Fi 时让用户自己拍板，不替他按「禁止」）。
 *
 * 示例数据（[UiState.Ready] 里的 `isSample`）与「没有可解析的比赛」时整块不渲染，
 * 不摆点了没反应的按钮。
 */
@Composable
fun BatchParseCard(
    state: UiState.Ready,
    demoState: DemoParseState?,
    networkType: () -> String,
    onStartQueue: () -> Unit,
    onCancelQueue: () -> Unit,
) {
    if (state.data.isSample) return

    val pending = DemoQueue.count(state.data.matches, isSample = false)
    val running = demoState?.queueActive == true
    if (!running && pending == 0) return

    var confirm by remember { mutableStateOf(false) }

    // 外层 Column 顺便把与下方列表的间距带上；不渲染时（上面的 return）也不会留下空档
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (running && demoState != null) {
                    Text("批量解析进行中", style = MaterialTheme.typography.titleSmall)
                    if (demoState.queueTotal > 0) {
                        LinearProgressIndicator(
                            progress = {
                                demoState.queueIndex.toFloat() / demoState.queueTotal.coerceAtLeast(1)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = demoState.message ?: "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCancelQueue) { Text("取消队列") }
                } else {
                    Text("批量补齐评分字段", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "$pending 场还没解析 · 按最早→最新排队（越早的比赛越接近 30 天过期）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { confirm = true }) { Text("批量解析 $pending 场 demo") }
                }
            }
        }
    }

    if (confirm) {
        // 打开确认框的这一刻取一次网络类型（之后网络变了也不改口，免得文案在眼前跳）
        val net = remember { networkType() }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("下载并解析 $pending 场 demo？") },
            text = {
                Text(
                    text = buildString {
                        append("每场回放约 70~150 MB，共 $pending 场，预计数百 MB 流量。\n\n")
                        append(
                            if (net == "Wi-Fi") "当前网络：Wi-Fi。"
                            else "⚠ 当前网络：$net —— 不在 Wi-Fi 下会走移动流量，确认要继续吗？",
                        )
                        append("\n\n任一场失败不会中断整队；已完成的场次不会重复下载。")
                        append("随时可取消，取消后未完成的下载会立刻丢弃。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; onStartQueue() }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            },
        )
    }
}
