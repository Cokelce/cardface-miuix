package com.dsh.miuix.cardface.core

/** Pure version ordering for upstream versions and this fork's oneplus.N builds. */
object UpdateVersion {
    private val pattern = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-oneplus\\.(\\d+))?$")

    fun fromTag(tag: String): String? {
        val value = tag.trim()
        return if (pattern.matches(value)) value.removePrefix("v").removePrefix("V") else null
    }

    fun compare(first: String, second: String): Int? {
        fun numbers(value: String): List<Long>? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            return (1..4).map { index ->
                val part = match.groupValues[index]
                if (part.isEmpty()) 0L else part.toLongOrNull() ?: return null
            }
        }
        val a = numbers(first) ?: return null
        val b = numbers(second) ?: return null
        for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    fun isNewer(latest: String, current: String): Boolean = (compare(latest, current) ?: 0) > 0
}
