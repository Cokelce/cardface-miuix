package com.dsh.miuix.cardface.ui

/** Android-free validation for persisted editor draft metadata. */
object DraftValidation {
    const val MAX_CROP_QUEUE = 100
    const val MAX_URI_LENGTH = 8_192
    private const val HASH_HEX_LENGTH = 24

    fun parseCrop(raw: String?): List<Float>? {
        val values = raw?.split(',')?.map { it.toFloatOrNull() ?: return null } ?: return null
        if (values.size != 4 || values.any { !it.isFinite() }) return null
        if (values[0] < 0f || values[1] < 0f || values[2] <= values[0] || values[3] <= values[1]) return null
        return values
    }

    fun urlHash(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    fun snapshotNameMatches(name: String, url: String): Boolean =
        name.matches(Regex("editor-draft-${urlHash(url)}-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.png"))

    fun validQueueUri(raw: String): Boolean = raw.length <= MAX_URI_LENGTH &&
        raw.contains(':') && raw.substringBefore(':').matches(Regex("[A-Za-z][A-Za-z0-9+.-]*")) &&
            raw.substringAfter(':').isNotBlank() && !raw.any(Char::isWhitespace)

    fun boundedQueue(values: Iterable<String>): List<String> =
        values.asSequence().filter(::validQueueUri).take(MAX_CROP_QUEUE).toList()
}
