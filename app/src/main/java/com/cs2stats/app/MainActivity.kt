package com.cs2stats.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cs2stats.app.ui.AppViewModel
import com.cs2stats.app.ui.MainScreen
import com.cs2stats.app.ui.theme.Cs2StatsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // 与 MainScreen 共用同一个 ViewModel：设置页一改主题，这里立刻跟着重绘
            val vm: AppViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            Cs2StatsTheme(
                darkTheme = settings.isDark(isSystemInDarkTheme()),
                oledDark = settings.useOledDark,
            ) {
                MainScreen(vm = vm)
            }
        }
    }
}
