package com.dsh.miuix.cardface.core

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** GitHub release metadata used by the in-app updater. */
object Updates {
    const val REPOSITORY = "Cokelce/cardface-miuix"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"
    const val DOWNLOAD_PREFIX = "https://github.com/$REPOSITORY/releases/download/"
    fun isNewer(latest: String, current: String): Boolean = UpdateVersion.isNewer(latest, current)

    data class Release(
        val versionName: String, val tag: String, val body: String,
        val htmlUrl: String, val downloadUrl: String = "",
        val expectedSize: Long = 0L, val sha256: String? = null,
    )
    data class ReleaseEntry(
        val date: String, val versionName: String, val body: String,
        val htmlUrl: String, val downloadUrl: String = "",
        val expectedSize: Long = 0L, val sha256: String? = null, val tag: String = "",
    )

    enum class Error { RATE_LIMIT, NETWORK, PARSE, HTTP }
    data class Check(val release: Release? = null, val error: Error? = null) {
        fun message(): String = when (error) {
            Error.RATE_LIMIT -> "GitHub 请求受限，请稍后重试。"
            Error.NETWORK -> "网络连接失败，请检查网络后重试。"
            Error.PARSE -> "更新信息解析失败，请稍后重试。"
            else -> "GitHub 服务返回异常，请稍后重试。"
        }
    }
    private class RequestFailure(val kind: Error) : Exception()
    private fun request(url: String, redirect: Boolean = true): Pair<String, String?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 10000; conn.useCaches = false
        conn.instanceFollowRedirects = redirect
        conn.setRequestProperty("User-Agent", "CardFace-OnePlus")
        try {
            val code = conn.responseCode
            if (!redirect && code in 300..399) return "" to conn.getHeaderField("Location")
            if (code != 200) throw RequestFailure(if (code == 429 || (code == 403 &&
                (conn.getHeaderField("X-RateLimit-Remaining") == "0" || conn.getHeaderField("Retry-After") != null ||
                 conn.errorStream?.bufferedReader()?.use { it.readText().contains("rate limit", true) } == true))) Error.RATE_LIMIT else Error.HTTP)
            return conn.inputStream.bufferedReader().use { it.readText() } to null
        } finally { conn.disconnect() }
    }
    private fun failure(t: Throwable): Error = when (t) {
        is RequestFailure -> t.kind
        is java.io.IOException -> Error.NETWORK
        else -> Error.PARSE
    }
    fun checkLatest(): Check {
        val api = runCatching { parseLatest(request(API).first) ?: throw IllegalArgumentException() }
        api.getOrNull()?.let { return Check(release = it) }
        val original = failure(api.exceptionOrNull()!!)
        val fallback = runCatching {
            val location = request("https://github.com/$REPOSITORY/releases/latest", false).second ?: throw IllegalArgumentException()
            val tag = tagFromLatestLocation(location) ?: throw IllegalArgumentException()
            val fragment = request("https://github.com/$REPOSITORY/releases/expanded_assets/$tag", false).first
            parseWebAssets(tag, fragment) ?: throw IllegalArgumentException()
        }
        return fallback.getOrNull()?.let { Check(release = it) } ?: Check(error =
            if (original == Error.RATE_LIMIT) original else failure(fallback.exceptionOrNull()!!).let { if (it == Error.PARSE) it else original })
    }
    fun fetchLatest(): Release? = checkLatest().release
    fun fetchReleases(): List<ReleaseEntry>? = runCatching { parseReleases(request(API).first) }.getOrNull()

    internal fun tagFromLatestLocation(location: String): String? {
        val prefix = "https://github.com/$REPOSITORY/releases/tag/"
        val value = if (location.startsWith("/$REPOSITORY/releases/tag/")) location.removePrefix("/$REPOSITORY/releases/tag/")
            else if (location.startsWith(prefix)) location.removePrefix(prefix) else return null
        val version = UpdateVersion.fromTag(value) ?: return null
        return value.takeIf { version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-oneplus\\.[0-9]+|-[0-9]{6}(?:[1-9][0-9]*)?)")) }
    }
    internal fun parseWebAssets(tag: String, html: String): Release? {
        if (tagFromLatestLocation("/$REPOSITORY/releases/tag/$tag") != tag) return null
        val version = UpdateVersion.fromTag(tag) ?: return null
        val prefix = "/$REPOSITORY/releases/download/$tag/"
        val links = Regex("href=[\"']([^\"']+)[\"']").findAll(html).map { it.groupValues[1] }
            .filter { it.startsWith(prefix) && it.removePrefix(prefix).matches(Regex("CardFace-[A-Za-z0-9._-]+\\.apk")) }.distinct().toList()
        if (links.size != 1) return null
        val link = links.single()
        val start = html.indexOf("href=\"$link\"").takeIf { it >= 0 } ?: return null
        val end = html.indexOf("</li>", start).takeIf { it >= 0 } ?: return null
        val chunk = html.substring(start, end)
        val hash = Regex("sha256:([0-9a-fA-F]{64})").find(chunk)?.groupValues?.get(1)?.lowercase() ?: return null
        return Release(version, tag, "", "https://github.com/$REPOSITORY/releases/tag/$tag", "https://github.com$link", 0, hash)
    }

    internal fun parseLatest(text: String): Release? {
        val latest = parseReleases(text).filter { it.downloadUrl.isNotBlank() }
            .maxWithOrNull { a, b -> UpdateVersion.compare(a.versionName, b.versionName) ?: 0 } ?: return null
        return Release(latest.versionName, latest.tag, latest.body, latest.htmlUrl, latest.downloadUrl, latest.expectedSize, latest.sha256)
    }

    internal fun parseReleases(text: String): List<ReleaseEntry> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            if (item.optBoolean("draft", false) || item.optBoolean("prerelease", false)) return@mapNotNull null
            val tag = item.optString("tag_name")
            val version = UpdateVersion.fromTag(tag) ?: return@mapNotNull null
            if (!version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-oneplus\\.[0-9]+|-[0-9]{6}(?:[1-9][0-9]*)?)"))) return@mapNotNull null
            val assets = item.optJSONArray("assets") ?: JSONArray()
            val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.firstOrNull {
                it.optString("name").startsWith("CardFace-") && it.optString("name").endsWith(".apk", true) &&
                    it.optString("state") == "uploaded" && it.optLong("size") > 0 &&
                    it.optString("browser_download_url").startsWith(DOWNLOAD_PREFIX)
            }
            val body = item.optString("body").trim()
            val digest = asset?.optString("digest").orEmpty().removePrefix("sha256:").lowercase()
                .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: SHA256_IN_BODY.find(body)?.groupValues?.get(1)?.lowercase()
            ReleaseEntry(item.optString("published_at").take(10), version, body,
                "https://github.com/$REPOSITORY/releases/tag/$tag", asset?.optString("browser_download_url").orEmpty(),
                asset?.optLong("size")?.takeIf { it > 0 } ?: 0L, digest, tag)
        }
    }

    private val SHA256_IN_BODY = Regex("(?i)(?:sha256|sha-256)\\s*[:=]\\s*([0-9a-f]{64})")
}
