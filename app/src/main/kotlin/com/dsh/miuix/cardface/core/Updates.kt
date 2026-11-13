package com.dsh.miuix.cardface.core

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Query this fork's public releases, including prereleases with downloadable APKs. */
object Updates {
    fun isNewer(latest: String, current: String): Boolean = UpdateVersion.isNewer(latest, current)

    data class Release(
        val versionName: String,
        val tag: String,
        val body: String,
        val htmlUrl: String,
        val downloadUrl: String,
        val size: Long = 0L,
        val sha256: String? = null,
    )

    const val REPOSITORY = "Cokelce/cardface-miuix"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"

    /** Drafts, unrelated tags and releases with no completed APK upload are excluded. */
    fun fetchLatest(): Release? = runCatching {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "cardface-miuix-oneplus")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            if (conn.responseCode != 200) return null
            parseReleases(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    internal fun parseReleases(text: String): Release? {
        val array = JSONArray(text)
        var latest: Release? = null
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optBoolean("draft", false)) continue
            val tag = item.optString("tag_name")
            val version = UpdateVersion.fromTag(tag) ?: continue
            val assets = item.optJSONArray("assets") ?: continue
            var download: String? = null
            var size = 0L
            var hash: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.startsWith("CardFace-") && name.endsWith(".apk", true) &&
                    asset.optString("state") == "uploaded" && asset.optLong("size") > 0 &&
                    url.startsWith("https://github.com/$REPOSITORY/releases/download/")) {
                    download = url
                    size = asset.optLong("size")
                    hash = UpdateIntegrity.sha256(asset.optString("digest"), item.optString("body"))
                    break
                }
            }
            if (download == null) continue
            val release = Release(version, tag, item.optString("body").trim(),
                "https://github.com/$REPOSITORY/releases/tag/$tag", download, size, hash)
            if (latest == null || isNewer(version, latest.versionName)) latest = release
        }
        return latest
    }
}
