package com.dsh.miuix.cardface.core

import java.security.MessageDigest

/** Evidence describes hook handoff, not screen rendering or payment success. */
object CardRuntime {
    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun state(replacement: String?, savedAt: Long, evidenceFingerprint: String?, hitAt: Long,
        errorAt: Long = 0, error: String? = null): String = when {
        replacement.isNullOrBlank() -> "未设置"
        evidenceFingerprint != fingerprint(replacement) -> "待加载"
        errorAt >= savedAt && errorAt > hitAt && !error.isNullOrBlank() -> "加载异常"
        hitAt > 0 && hitAt >= savedAt -> "已命中"
        else -> "待加载"
    }
}

/** Conservative grouping: preserve host and UUID; remove only known image-size transformations. */
object CardIdentity {
    private val sizeSegment = Regex("/w\\d+h\\d+(?=/|$)")
    private val walletTransform = Regex("(?i)(\\.(?:png|jpe?g))(?:\\.-[a-z0-9]+)*\\.webp$")
    private val volatileQuery = setOf("w", "h", "width", "height", "size", "q", "quality", "format", "fmt",
        "thumbnail", "thumb", "resize", "scale", "x-oss-process", "imageview2", "ts", "t", "v", "_",
        "sign", "signature", "expires", "expire", "token")
    private val thumbnailHash = Regex("(/mfc/thumbnail/webp/)[0-9a-fA-F]{12,}/")
    fun key(url: String): String {
        val plain = url.substringBefore('#')
        val path = thumbnailHash.replace(walletTransform.replace(
            sizeSegment.replace(plain.substringBefore('?'), ""), "$1"), "$1")
        val query = plain.substringAfter('?', "").split('&').filter { it.isNotBlank() }
            .filterNot { it.substringBefore('=').lowercase() in volatileQuery }.sorted()
        return path + if (query.isEmpty()) "" else "?" + query.joinToString("&")
    }

    fun group(items: List<CardFaceItem>): List<CardFaceItem> = items.groupBy { key(it.url) }.values.flatMap { group ->
        if (group.mapNotNull { it.replacement }.distinct().size > 1 ||
            group.mapNotNull { it.customName }.distinct().size > 1) return@flatMap group
        val chosen = group.sortedWith(compareByDescending<CardFaceItem> { !it.customName.isNullOrBlank() }
            .thenByDescending { it.replaced }.thenByDescending { !it.detectedName.isNullOrBlank() }
            .thenBy { it.url }).first()
        listOf(chosen.copy(aliasCount = group.size,
            customName = chosen.customName ?: group.firstNotNullOfOrNull { it.customName },
            detectedName = chosen.detectedName ?: group.firstNotNullOfOrNull { it.detectedName }))
    }.sortedBy { it.url }
}
