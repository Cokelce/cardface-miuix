package com.dsh.miuix.cardface.core.catalog

/** 特摄分类：奥特曼 / 假面骑士 / 铁甲小宝 / 超级战队等，单开一栏，不混进二次元。 */
object TokusatsuIps {

    private val exact = setOf("奥特曼", "假面骑士", "铁甲小宝", "超级战队", "牙狼", "哥斯拉")
    private val keywords = listOf("奥特曼", "奥特", "假面骑士", "铁甲小宝", "超级战队", "牙狼", "哥斯拉", "特摄")

    fun isTokusatsu(name: String): Boolean {
        val text = name.trim()
        if (text.isEmpty()) return false
        if (text in exact) return true
        return keywords.any { text.contains(it, ignoreCase = true) }
    }
}
