package com.dsh.miuix.cardface.ui

import android.os.Build
import android.icu.text.Transliterator
import java.util.concurrent.ConcurrentHashMap

/**
 * 通讯录式字母索引：把分组名（银行 / 城市 / IP）映射到首字母。
 * 中文用系统 ICU 的 Han-Latin 转拼音再取首字母；低版本系统没有 ICU 转写时归到 #。
 */
object LetterIndex {

    const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#"

    private val cache = ConcurrentHashMap<String, Char>()
    private val hanLatin: Transliterator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) Transliterator.getInstance("Han-Latin") else null
        }.getOrNull()
    }

    fun initial(name: String): Char {
        val text = name.trim()
        if (text.isEmpty()) return '#'
        cache[text]?.let { return it }
        val first = text[0]
        val result = when {
            first in 'A'..'Z' -> first
            first in 'a'..'z' -> first.uppercaseChar()
            first in '0'..'9' -> '#'
            else -> runCatching {
                val latin = hanLatin?.transliterate(first.toString()).orEmpty()
                latin.firstOrNull { it in 'a'..'z' }?.uppercaseChar() ?: '#'
            }.getOrDefault('#')
        }
        cache[text] = result
        return result
    }
}
