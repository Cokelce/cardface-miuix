package com.dsh.miuix.cardface.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Release 查询（只用于「检查更新 / 更新公告」）。 */
object Updates {

    /** Compare semantic version, date, and same-day build suffix. */
    fun isNewer(latest: String, current: String): Boolean = UpdateVersion.isNewer(latest, current)

    data class Release(
        val versionName: String,
        val tag: String,
        val body: String,
        val htmlUrl: String,
        val downloadUrl: String = "",
    )

    data class ReleaseEntry(
        val date: String,
        val versionName: String,
        val body: String,
        val htmlUrl: String,
        val downloadUrl: String = "",
    )

    private const val API =
        "https://api.github.com/repos/Cokelce/cardface-miuix/releases/latest"
    private const val API_LIST =
        "https://api.github.com/repos/Cokelce/cardface-miuix/releases?per_page=20"

    /** 网络请求失败返回 null，调用方按「检查失败」处理。 */
    fun fetchLatest(): Release? = runCatching {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "cardface-miuix")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            val version = tag.removePrefix("v").removePrefix("V")
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            Release(
                versionName = version,
                tag = tag,
                body = json.optString("body").trim(),
                htmlUrl = json.optString("html_url"),
                downloadUrl = apkUrl,
            )
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 更新公告列表（最近 20 个 Release），失败返回 null。 */
    fun fetchReleases(): List<ReleaseEntry>? = runCatching {
        val conn = URL(API_LIST).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "cardface-miuix")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val json = array.optJSONObject(i) ?: return@mapNotNull null
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@mapNotNull null
                val tag = json.optString("tag_name")
                val version = tag.removePrefix("v").removePrefix("V")
                ReleaseEntry(
                    date = json.optString("published_at").take(10),
                    versionName = version,
                    body = json.optString("body").trim(),
                    htmlUrl = json.optString("html_url"),
                )
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
