package com.cs2stats.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cs2stats.app.ui.screens.HomeScreen
import com.cs2stats.app.ui.screens.MatchDetailScreen
import com.cs2stats.app.ui.screens.MatchesScreen
import com.cs2stats.app.ui.screens.SettingsScreen
import com.cs2stats.app.ui.screens.SteamLoginScreen

private data class Tab(val route: String, val label: String)

private val tabs = listOf(
    Tab("home", "总览"),
    Tab("matches", "比赛"),
    Tab("settings", "设置"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // 解析/批量队列状态提到顶层：详情页与比赛列表页共用同一份（服务是进程级的）
    val demoState by vm.demoState.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"

    val title = when {
        route.startsWith("match/") -> "比赛详情"
        route == "steam_login" -> "Steam 登录"
        else -> tabs.firstOrNull { it.route == route }?.label ?: "CS2 战绩"
    }

    val isRootTab = tabs.any { it.route == route }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (!isRootTab) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            // NavHost 还没 setGraph()（首帧还在 Loading）时 `nav.graph` 会抛
                            // IllegalStateException —— 真机复现过：加载中点底部标签直接崩整个 App。
                            // currentDestination 为 null 即图还没挂上，这次点击直接忽略。
                            if (nav.currentDestination != null) {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (tab.route) {
                                    "home" -> Icons.Filled.Dashboard
                                    "matches" -> Icons.Filled.SportsEsports
                                    else -> Icons.Filled.Settings
                                },
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (val state = ui) {
            is UiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is UiState.Ready -> NavHost(
                navController = nav,
                startDestination = "home",
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                composable("home") {
                    HomeScreen(
                        state = state,
                        onOpenMatch = { nav.navigate("match/$it") },
                        onSeeAll = {
                            nav.navigate("matches") {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable("matches") {
                    MatchesScreen(
                        state = state,
                        onOpenMatch = { nav.navigate("match/$it") },
                        demoState = demoState,
                        networkType = vm::networkType,
                        onStartQueue = vm::startDemoQueue,
                        onCancelQueue = vm::cancelDemoParse,
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        vm = vm,
                        state = state,
                        onSteamLogin = { nav.navigate("steam_login") },
                    )
                }
                composable("steam_login") {
                    SteamLoginScreen(
                        onLoggedIn = { steamId ->
                            vm.onSteamLogin(steamId)
                            nav.popBackStack()
                        },
                        onClose = { nav.popBackStack() },
                    )
                }
                composable("match/{matchId}") { entry ->
                    val id = entry.arguments?.getString("matchId").orEmpty()
                    MatchDetailScreen(
                        state = state,
                        matchId = id,
                        demoState = demoState,
                        onStartParse = vm::startDemoParse,
                        onCancelParse = vm::cancelDemoParse,
                    )
                }
            }
        }
    }
}
