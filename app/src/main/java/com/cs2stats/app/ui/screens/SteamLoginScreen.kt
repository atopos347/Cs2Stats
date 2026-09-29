package com.cs2stats.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cs2stats.app.ui.steam.SteamOpenId

/**
 * Steam OpenID 登录页（应用内 WebView）。
 *
 * 流程：加载授权地址 → 用户在 Steam 页面登录并确认 → Steam 302 到本地占位回调，
 * WebView 拦截 `openid.claimed_id` 里的 SteamID64 → [onLoggedIn] 并自动返回。
 *
 * 不需要自己的公网回调服务器：占位地址永远不会被真正访问，只用来承接 query。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SteamLoginScreen(
    onLoggedIn: (steamId: String) -> Unit,
    onClose: () -> Unit,
) {
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)

    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var cancelled by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // 去掉 WebView 标识，降低被 Steam 风控拦截的概率
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val (steamId, isCallback) = SteamOpenId.parse(request.url.toString())
                    if (!isCallback) return false
                    if (steamId != null) currentOnLoggedIn(steamId) else cancelled = true
                    return true // 不真正加载这个本地占位地址
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loading = true
                    progress = 0.35f
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    progress = 0f
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        errorText = "页面加载失败：${error?.description ?: "网络错误"}"
                        loading = false
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        webView.loadUrl(SteamOpenId.authUrl())
        onDispose { webView.destroy() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

        if (cancelled || errorText != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = errorText ?: "你取消了 Steam 授权，没有取到 SteamID64。",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 56.dp, bottom = 20.dp),
                )
                Button(onClick = {
                    cancelled = false
                    errorText = null
                    loading = true
                    progress = 0f
                    webView.loadUrl(SteamOpenId.authUrl())
                }) { Text("重新授权") }
                Button(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) {
                    Text("返回设置")
                }
            }
        } else if (loading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            )
        }
    }
}
