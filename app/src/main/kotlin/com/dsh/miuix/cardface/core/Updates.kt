package com.dsh.miuix.cardface.core

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** All update views use this fork, including prereleases with completed APK uploads. */
object Updates {
    const val REPOSITORY = "Cokelce/cardface-miuix"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"
    fun isNewer(latest: String, current: String): Boolean = UpdateVersion.isNewer(latest, current)

    data class Release(val versionName: String, val tag: String, val body: String,
        val htmlUrl: String, val downloadUrl: String = "")
    data class ReleaseEntry(val date: String, val versionName: String, val body: String,
        val htmlUrl: String, val downloadUrl: String = "")

    private fun request(): String? = runCatching {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "CardFace-OnePlus")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally { conn.disconnect() }
    }.getOrNull()

    fun fetchLatest(): Release? = request()?.let { runCatching { parseLatest(it) }.getOrNull() }
    fun fetchReleases(): List<ReleaseEntry>? = request()?.let { runCatching { parseReleases(it) }.getOrNull() }

    internal fun parseLatest(text: String): Release? {
        val latest = parseReleases(text).filter { it.downloadUrl.isNotBlank() }
            .maxWithOrNull { a, b -> UpdateVersion.compare(a.versionName, b.versionName) ?: 0 } ?: return null
        val tag = JSONArray(text).let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .firstOrNull { UpdateVersion.fromTag(it.optString("tag_name")) == latest.versionName }
                ?.optString("tag_name") ?: "v${latest.versionName}"
        }
        return Release(latest.versionName, tag, latest.body, latest.htmlUrl, latest.downloadUrl)
    }

    internal fun parseReleases(text: String): List<ReleaseEntry> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            if (item.optBoolean("draft", false)) return@mapNotNull null
            val tag = item.optString("tag_name")
            val version = UpdateVersion.fromTag(tag) ?: return@mapNotNull null
            // Upstream-only releases must never replace this fork's wallet module.
            if (!version.contains("-oneplus.")) return@mapNotNull null
            val assets = item.optJSONArray("assets") ?: JSONArray()
            val download = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .firstOrNull {
                    it.optString("name").startsWith("CardFace-") &&
                    it.optString("name").endsWith(".apk", ignoreCase = true) &&
                    it.optString("state") == "uploaded" && it.optLong("size") > 0 &&
                    it.optString("browser_download_url").startsWith("https://github.com/$REPOSITORY/releases/download/")
                }?.optString("browser_download_url").orEmpty()
            ReleaseEntry(item.optString("published_at").take(10), version, item.optString("body").trim(),
                "https://github.com/$REPOSITORY/releases/tag/$tag", download)
        }
    }
}
