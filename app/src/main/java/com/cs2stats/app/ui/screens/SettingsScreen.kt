package com.cs2stats.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cs2stats.app.data.collect.SteamWebSession
import com.cs2stats.app.data.local.AppSettings
import com.cs2stats.app.data.rating.Rating3
import com.cs2stats.app.ui.AppViewModel
import com.cs2stats.app.ui.UiState
import com.cs2stats.app.ui.components.InfoBanner
import com.cs2stats.app.ui.components.SectionTitle
import kotlinx.coroutines.delay

/** 设置：数据空间（示例数据 / 网盘 / 服务器）、Steam 登录、预留数据源、算法口径。 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    state: UiState.Ready,
    onSteamLogin: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    var storageMode by remember(settings.storageMode) { mutableStateOf(settings.storageMode) }
    var davUrl by remember(settings.davUrl) { mutableStateOf(settings.davUrl) }
    var davUser by remember(settings.davUser) { mutableStateOf(settings.davUser) }
    var davPass by remember(settings.davPass) { mutableStateOf(settings.davPass) }
    var serverUrl by remember(settings.serverBaseUrl) { mutableStateOf(settings.serverBaseUrl) }
    var apiToken by remember(settings.apiToken) { mutableStateOf(settings.apiToken) }
    var steamId by remember(settings.steamId) { mutableStateOf(settings.steamId) }
    var steamName by remember(settings.steamName) { mutableStateOf(settings.steamName) }
    var faceitKey by remember(settings.faceitApiKey) { mutableStateOf(settings.faceitApiKey) }

    // 把页面上的输入合成一份完整设置：所有操作都用它，避免「先保存再执行」的竞态
    val snapshot: (AppSettings) -> AppSettings = {
        it.copy(
            storageMode = storageMode,
            davUrl = davUrl,
            davUser = davUser,
            davPass = davPass,
            serverBaseUrl = serverUrl,
            apiToken = apiToken,
        )
    }

    // 提示 8 秒后自动消失
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(8_000)
            vm.clearNotice()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(2.dp))

        InfoBanner(text = state.data.sourceLabel + (state.data.error?.let { "\n⚠ $it" } ?: ""))
        notice?.let { InfoBanner(it, isError = it.startsWith("❌")) }

        // ---------- 外观 ----------
        SectionTitle("外观")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("跟随系统", settings.themeMode == AppSettings.THEME_FOLLOW) {
                vm.updateSettings { it.copy(themeMode = AppSettings.THEME_FOLLOW) }
            }
            ModeChip("浅色", settings.themeMode == AppSettings.THEME_LIGHT) {
                vm.updateSettings { it.copy(themeMode = AppSettings.THEME_LIGHT) }
            }
            ModeChip("深色", settings.themeMode == AppSettings.THEME_DARK) {
                vm.updateSettings { it.copy(themeMode = AppSettings.THEME_DARK) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("OLED 纯黑", settings.useOledDark) {
                vm.updateSettings { it.copy(darkStyle = AppSettings.DARK_OLED) }
            }
            ModeChip("LCD 深灰", settings.darkStyle == AppSettings.DARK_LCD) {
                vm.updateSettings { it.copy(darkStyle = AppSettings.DARK_LCD) }
            }
        }

        Text(
            text = "「跟随系统」随手机日夜模式自动切换。暗色样式只在深色下生效：" +
                "OLED 纯黑把底色压到 #000000（像素点直接熄灭，省电、黑得彻底）；" +
                "LCD 深灰是常规深色底，层次更柔和。Android 12+ 的壁纸取色在两种样式里都保留。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // ---------- 数据空间 ----------
        SectionTitle("数据空间")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("示例数据", storageMode == AppSettings.MODE_MOCK) {
                storageMode = AppSettings.MODE_MOCK
                vm.updateSettings(snapshot)
            }
            ModeChip("网盘同步", storageMode == AppSettings.MODE_WEBDAV) {
                storageMode = AppSettings.MODE_WEBDAV
                vm.updateSettings(snapshot)
            }
            ModeChip("自建服务器", storageMode == AppSettings.MODE_SERVER) {
                storageMode = AppSettings.MODE_SERVER
                vm.updateSettings(snapshot)
            }
        }

        when (storageMode) {
            AppSettings.MODE_WEBDAV -> {
                Text(
                    text = "用 WebDAV 网盘当数据库：把比赛记录存成 JSON 文件，手机读回来本地算 Rating。" +
                        "坚果云：网页版 → 设置 → 安全选项 → 第三方应用管理 → 添加应用，得到**应用密码**。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = davUrl,
                    onValueChange = { davUrl = it },
                    label = { Text("网盘地址（WebDAV）") },
                    placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = davUser,
                    onValueChange = { davUser = it },
                    label = { Text("账号（登录邮箱）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = davPass,
                    onValueChange = { davPass = it },
                    label = { Text("应用密码（非登录密码）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.uploadToCloud(snapshot) }, modifier = Modifier.weight(1f)) {
                        Text("上传到网盘")
                    }
                    OutlinedButton(onClick = { vm.refresh(snapshot) }, modifier = Modifier.weight(1f)) {
                        Text("下载同步")
                    }
                }
                OutlinedButton(
                    onClick = { vm.testCloud(snapshot) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("测试连接") }
            }

            AppSettings.MODE_SERVER -> {
                Text(
                    text = "自建 HTTP 后端（接口契约见 README），备用方案。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://api.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiToken,
                    onValueChange = { apiToken = it },
                    label = { Text("访问令牌（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { vm.refresh(snapshot) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存并拉取") }
            }

            else -> {
                Text(
                    text = "完全离线的内置示例数据（固定种子生成），用于跑通界面与算法，不联网。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        // ---------- Steam 登录 ----------
        SectionTitle("Steam 账号")

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = settings.avatarUrl,
                    contentDescription = "Steam 头像",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            if (settings.isSteamLoggedIn) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (settings.isSteamLoggedIn) "✓" else "?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column {
                Text(
                    text = settings.steamName.ifBlank {
                        if (settings.isSteamLoggedIn) "（资料未公开，未取到昵称）" else "未登录"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (settings.isSteamLoggedIn) {
                    Text(
                        text = "SteamID64：${settings.steamId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            text = "应用内授权走 Steam OpenID：登录并确认后自动回填 SteamID64、昵称与头像，" +
                "并据此在战绩表里定位「我」的那一行。比赛战绩需资料设为公开才能采集。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSteamLogin, modifier = Modifier.fillMaxWidth()) {
            Text("Steam 登录（应用内授权）")
        }

        // ---------- 官匹数据采集 ----------
        OutlinedButton(
            onClick = { vm.collectFromSteam() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (remember(settings.steamId) { SteamWebSession.isLoggedIn() }) {
                    "从 Steam 抓取最近比赛（已登录）"
                } else "从 Steam 抓取最近比赛（需先登录）"
            )
        }
        Text(
            text = "抓取「我的游戏数据」的比赛记录（优先匹配 / 竞技 / 历史，ajax 分页，自动续期会话），" +
                "按 demo 主键去重。原始 HTML 会存到应用私有目录，用于校准地图/比分/击杀等字段的解析。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { uriHandler.openUri("https://steamcommunity.com/") },
                modifier = Modifier.weight(1f),
            ) { Text("打开社区") }
            OutlinedButton(
                onClick = {
                    vm.updateSettings { it.copy(steamId = steamId, steamName = steamName) }
                },
                modifier = Modifier.weight(1f),
            ) { Text("手动保存信息") }
        }
        OutlinedButton(
            onClick = {
                vm.updateSettings { it.copy(steamId = "", steamName = "", avatarUrl = "") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("退出登录") }

        HorizontalDivider()

        // ---------- 预留数据源 ----------
        SectionTitle("预留数据源接口")
        OutlinedTextField(
            value = faceitKey,
            onValueChange = { faceitKey = it },
            label = { Text("FACEIT API Key（预留）") },
            supportingText = { Text("已按需求预留，当前不参与拉取；接入时在此开关。") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { vm.updateSettings { it.copy(faceitApiKey = faceitKey) } }) {
            Text("保存")
        }

        HorizontalDivider()

        // ---------- 算法口径 ----------
        SectionTitle("Rating 3.0 计算口径")
        Text(
            text = "六子项：击杀 / 伤害 / 生存 / KAST / 多杀 / 回合影响力(Round Swing)，" +
                "按 HLTV 公开的「产出 60% · 代价 40%」合成，赛事均值 1.00。" +
                "HLTV 未公开精确系数，本实现为结构一致的自定标定近似。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Rating3.describeWeights(),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "当前账号：" + (
                if (settings.isSteamLoggedIn) {
                    "${settings.steamName.ifBlank { "已登录" }} · ${settings.steamId}"
                } else "未绑定 SteamID64"
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
