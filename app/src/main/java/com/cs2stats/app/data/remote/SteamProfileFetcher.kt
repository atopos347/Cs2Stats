package com.cs2stats.app.data.remote

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * 登录成功后回填昵称与头像：读取 Steam 社区的 XML 资料页（`?xml=1`）。
 *
 * 资料**设为公开**才能拿到；私密资料会返回 `<error>`，此时静默返回 null，
 * App 仍可用 SteamID64 继续工作（只是不显示昵称头像）。
 */
object SteamProfileFetcher {

    data class Fetched(val personaName: String, val avatarUrl: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(steamId: String): Fetched? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://steamcommunity.com/profiles/$steamId?xml=1")
                .header("User-Agent", "Mozilla/5.0 (compatible; Cs2Stats/0.1)")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val xml = resp.body?.string().orEmpty()
                if (xml.contains("<error>")) return@runCatching null
                parse(xml)
            }
        }.getOrNull()
    }

    private fun parse(xml: String): Fetched? {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var name = ""
        var avatar = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "steamID" -> if (name.isEmpty()) name = parser.nextText().trim()
                    "avatarFull" -> if (avatar.isEmpty()) avatar = parser.nextText().trim()
                }
            }
            event = parser.next()
        }
        return if (name.isEmpty()) null else Fetched(name, avatar)
    }
}
