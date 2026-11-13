package com.dsh.miuix.cardface.core

/** Reads only display-name getters; never account/card numbers or holder information. */
object CardNameResolver {
    private val getters = listOf("getDisplayName", "getCardName", "getSchoolName")
    private val cachedGetters = java.util.concurrent.ConcurrentHashMap<Class<*>, List<java.lang.reflect.Method>>()

    fun clean(value: String?): String? = value?.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
        ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 &&
            !it.equals("null", true) && !it.startsWith("http", true) }

    fun fromCard(card: Any?): String? {
        if (card == null) return null
        val methods = cachedGetters.getOrPut(card.javaClass) {
            getters.mapNotNull { name -> runCatching { card.javaClass.getMethod(name) }.getOrNull() }
                .filter { it.returnType == String::class.java && it.parameterCount == 0 }
        }
        return methods.firstNotNullOfOrNull { method ->
            clean(runCatching { method.invoke(card) as? String }.getOrNull())
        }
    }

    fun displayName(custom: String?, detected: String?, url: String): String {
        clean(custom)?.let { return it }
        clean(detected)?.let { return it }
        val raw = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return when {
            decoded.isBlank() -> "未命名卡面"
            decoded.matches(Regex("^[0-9a-fA-F]{24,}$")) -> "卡面 " + decoded.take(8)
            else -> decoded
        }
    }
}
