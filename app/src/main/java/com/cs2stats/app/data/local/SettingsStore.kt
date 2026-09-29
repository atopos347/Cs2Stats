package com.cs2stats.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 应用设置。
 *
 * 数据空间三选一（[storageMode]）：
 * - [MODE_MOCK]：内置示例数据，完全离线；
 * - [MODE_WEBDAV]：**坚果云等 WebDAV 网盘**，把 JSON 文件当数据库（默认方案）；
 * - [MODE_SERVER]：自建 HTTP 后端，接口契约见 README（保留备用）。
 *
 * 其余字段：[steamId] / [steamName] / [avatarUrl] 由 Steam OpenID 登录回填；
 * [faceitApiKey] 按需求预留的第三方数据源接口，当前不参与拉取。
 *
 * 外观两档：
 * - [themeMode]：浅色 / 深色 / **跟随系统**（默认，随系统日夜状态自动切换）；
 * - [darkStyle]：进入深色后用哪种底 —— [DARK_OLED] 纯黑（省电、对比强）还是 [DARK_LCD] 深灰（层次柔和）。
 */
data class AppSettings(
    val storageMode: String = MODE_MOCK,
    val davUrl: String = "",
    val davUser: String = "",
    val davPass: String = "",
    val serverBaseUrl: String = "",
    val apiToken: String = "",
    val steamId: String = "",
    val steamName: String = "",
    val avatarUrl: String = "",
    val faceitApiKey: String = "",
    val themeMode: String = THEME_FOLLOW,
    val darkStyle: String = DARK_OLED,
) {
    val useMockData: Boolean get() = storageMode == MODE_MOCK

    val hasServer: Boolean get() = storageMode == MODE_SERVER && serverBaseUrl.startsWith("http")

    /** WebDAV 是否已配置到可用程度（地址 + 账号 + 应用密码齐了）。 */
    val hasWebDav: Boolean
        get() = storageMode == MODE_WEBDAV &&
            davUrl.startsWith("http") && davUser.isNotBlank() && davPass.isNotBlank()

    val isSteamLoggedIn: Boolean get() = steamId.isNotBlank()

    /**
     * 现在该不该用深色。[THEME_FOLLOW]（默认）与任何不认识的取值都跟着系统走 ——
     * 系统的日夜状态由 Compose 侧 `isSystemInDarkTheme()` 传进来。
     */
    fun isDark(systemDark: Boolean): Boolean = when (themeMode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> systemDark
    }

    /** 深色时用 OLED 纯黑底（否则用普通 LCD 深灰底）。浅色主题下不起作用。 */
    val useOledDark: Boolean get() = darkStyle == DARK_OLED

    /** 数据来源的可读标签。 */
    val sourceLabel: String
        get() = when {
            useMockData -> "本地示例数据"
            hasWebDav -> "网盘同步（${davUrl}）"
            storageMode == MODE_WEBDAV -> "网盘未配置完整（缺地址/账号/应用密码）"
            hasServer -> "服务器 ${serverBaseUrl}"
            else -> "未配置数据空间"
        }

    companion object {
        const val MODE_MOCK = "mock"
        const val MODE_WEBDAV = "webdav"
        const val MODE_SERVER = "server"

        /** 主题模式：跟随系统（默认）/ 浅色 / 深色。 */
        const val THEME_FOLLOW = "follow"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** 深色样式：OLED 纯黑（默认）/ LCD 深灰。 */
        const val DARK_OLED = "oled"
        const val DARK_LCD = "lcd"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "cs2stats_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val STORAGE_MODE = stringPreferencesKey("storage_mode")
        val DAV_URL = stringPreferencesKey("dav_url")
        val DAV_USER = stringPreferencesKey("dav_user")
        val DAV_PASS = stringPreferencesKey("dav_pass")
        val SERVER = stringPreferencesKey("server_base_url")
        val TOKEN = stringPreferencesKey("api_token")
        val STEAM_ID = stringPreferencesKey("steam_id")
        val STEAM_NAME = stringPreferencesKey("steam_name")
        val AVATAR = stringPreferencesKey("avatar_url")
        val FACEIT_KEY = stringPreferencesKey("faceit_api_key")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DARK_STYLE = stringPreferencesKey("dark_style")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p -> read(p) }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val next = transform(read(p))
            p[Keys.STORAGE_MODE] = next.storageMode
            p[Keys.DAV_URL] = next.davUrl.trim()
            p[Keys.DAV_USER] = next.davUser.trim()
            p[Keys.DAV_PASS] = next.davPass.trim()
            p[Keys.SERVER] = next.serverBaseUrl.trim()
            p[Keys.TOKEN] = next.apiToken.trim()
            p[Keys.STEAM_ID] = next.steamId.trim()
            p[Keys.STEAM_NAME] = next.steamName.trim()
            p[Keys.AVATAR] = next.avatarUrl.trim()
            p[Keys.FACEIT_KEY] = next.faceitApiKey.trim()
            p[Keys.THEME_MODE] = next.themeMode
            p[Keys.DARK_STYLE] = next.darkStyle
        }
    }

    private fun read(p: Preferences): AppSettings = AppSettings(
        storageMode = p[Keys.STORAGE_MODE] ?: AppSettings.MODE_MOCK,
        davUrl = p[Keys.DAV_URL] ?: "",
        davUser = p[Keys.DAV_USER] ?: "",
        davPass = p[Keys.DAV_PASS] ?: "",
        serverBaseUrl = p[Keys.SERVER] ?: "",
        apiToken = p[Keys.TOKEN] ?: "",
        steamId = p[Keys.STEAM_ID] ?: "",
        steamName = p[Keys.STEAM_NAME] ?: "",
        avatarUrl = p[Keys.AVATAR] ?: "",
        faceitApiKey = p[Keys.FACEIT_KEY] ?: "",
        // 取值不认识时回落到默认档，宁可退化也不能把界面染成没配过的颜色
        themeMode = (p[Keys.THEME_MODE] ?: AppSettings.THEME_FOLLOW).takeIf {
            it == AppSettings.THEME_FOLLOW || it == AppSettings.THEME_LIGHT || it == AppSettings.THEME_DARK
        } ?: AppSettings.THEME_FOLLOW,
        darkStyle = (p[Keys.DARK_STYLE] ?: AppSettings.DARK_OLED).takeIf {
            it == AppSettings.DARK_OLED || it == AppSettings.DARK_LCD
        } ?: AppSettings.DARK_OLED,
    )
}
