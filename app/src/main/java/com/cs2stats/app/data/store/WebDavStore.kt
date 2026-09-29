package com.cs2stats.app.data.store

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebDAV 存储实现（坚果云、Nextcloud、群晖等通用）。
 *
 * 用法（坚果云为例）：
 * 1. 网页版 → 设置 → 安全选项 → **第三方应用管理** → 添加应用，得到「应用密码」；
 * 2. 设置页填：
 *    - 地址：`https://dav.jianguoyun.com/dav/`
 *    - 账号：登录邮箱
 *    - 应用密码：上面生成的那串（**不是登录密码**）
 *
 * 实现要点：
 * - Basic 认证（坚果云要求用应用密码，不接受登录密码）；
 * - `GET` 读、`PUT` 写，`MKCOL` 逐级补父目录（已存在返回 405，忽略即可）；
 * - `PROPFIND Depth:0` 作为连通性自测，401 表示账号或应用密码不对。
 */
class WebDavStore(
    baseUrl: String,
    username: String,
    password: String,
    private val root: String = "Cs2Stats",
) : SyncStore {

    private val base = baseUrl.trim().trimEnd('/') + "/"
    private val auth = "Basic " + Base64.encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP,
    )

    override val label: String = runCatching { base.toHttpUrl().host }.getOrDefault("WebDAV")

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun authorized(url: String, method: String, body: okhttp3.RequestBody? = null): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("User-Agent", "Cs2Stats/0.1 (Android)")
            .method(method, body)
            .build()

    /** 完整路径：base + 根目录 + 文件路径。 */
    private fun urlOf(path: String): String = base + root + "/" + path.trimStart('/')

    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        http.newCall(authorized(urlOf(path), "GET")).execute().use { resp ->
            when (resp.code) {
                200 -> resp.body?.string()
                404 -> null
                401 -> throw IOException("网盘账号或应用密码不对（401）")
                else -> throw IOException("读取 $path 失败：HTTP ${resp.code}")
            }
        }
    }

    override suspend fun write(path: String, content: String) = withContext(Dispatchers.IO) {
        ensureDirs(path)
        val req = authorized(
            urlOf(path),
            "PUT",
            content.toRequestBody("application/json; charset=utf-8".toMediaType()),
        )
        http.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) {
                throw IOException("写入 $path 失败：HTTP ${resp.code} ${resp.message}")
            }
        }
    }

    /** 逐级创建父目录；已存在(405)、方法不允许(405/301)都视为成功。 */
    private fun ensureDirs(path: String) {
        val dirs = path.trimStart('/').split('/').dropLast(1)
        var acc = root
        dirs.forEach { seg ->
            if (seg.isBlank()) return@forEach
            acc += "/$seg"
            val req = authorized(base + acc, "MKCOL")
            http.newCall(req).execute().use { /* 201 建好 / 405 已存在，都可接受 */ }
        }
    }

    override suspend fun test(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(base + root)
            .header("Authorization", auth)
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                in 200..299 -> true
                404 -> {
                    // 根目录还不存在：试着建出来，成功即视为连通
                    val mk = authorized(base + root, "MKCOL")
                    http.newCall(mk).execute().use { it.code in 200..299 || it.code == 405 }
                }
                401 -> throw IOException("账号或应用密码不对（401）")
                else -> false
            }
        }
    }

    /** 供设置页显示：去掉路径的主机名。 */
    fun host(): String = label
}
