package com.dsh.miuix.cardface.core

import java.io.File
import java.util.zip.ZipFile

object UpdateArtifactPolicy {
    const val MAX_BYTES = 128L * 1024L * 1024L
    private val VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+-oneplus\\.[0-9]+$")
    private val HASH = Regex("^[0-9a-f]{64}$")

    fun safeVersion(version: String): String = version.takeIf { VERSION.matches(it) } ?: "unknown"
    fun safeHash(hash: String): String = hash.lowercase().takeIf { HASH.matches(it) } ?: "unknown"
    fun finalName(version: String, hash: String): String = "${safeVersion(version)}-${safeHash(hash)}.apk"
    fun validateSize(size: Long, expected: Long): Boolean = size in 1..MAX_BYTES && (expected <= 0L || size == expected)
    fun allowedDownloadUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }.getOrDefault(false)
    fun validateZip(file: File): Boolean = runCatching {
        ZipFile(file).use { zip -> listOf("AndroidManifest.xml", "classes.dex", "resources.arsc").all { name ->
            val entry = zip.getEntry(name)
            entry != null && !entry.isDirectory && entry.size > 0L
        } }
    }.getOrDefault(false)
}
