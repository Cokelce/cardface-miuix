package com.dsh.miuix.cardface.core

import org.json.JSONObject

object MappingSnapshot {
    fun encode(mappings: Map<String, String>, revision: Long): String = JSONObject()
        .put("schema", 2).put("revision", revision).put("mappings", JSONObject(mappings)).toString()

    fun decode(text: String): MappingPolicy.State {
        require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "映射快照过大" }
        if (!text.trimStart().startsWith("{")) {
            val old = linkedMapOf<String, String>()
            text.lineSequence().forEach { line ->
                val tab = line.indexOf('\t')
                if (tab in 1 until line.length - 1) old[line.substring(0, tab)] = line.substring(tab + 1)
            }
            return MappingPolicy.State(validate(old), 0)
        }
        val json = JSONObject(text)
        require(json.getInt("schema") == 2) { "映射快照版本不匹配" }
        val data = json.getJSONObject("mappings")
        val mappings = data.keys().asSequence().associateWith { data.getString(it) }
        return MappingPolicy.State(validate(mappings), json.getLong("revision"))
    }
    private fun validate(map: Map<String, String>): Map<String, String> {
        require(map.size <= 5000)
        require(map.all { (key, value) ->
            (key.startsWith("https://") || key.startsWith("http://")) && key.length <= 8192 &&
                key.none { it.code < 32 } && value.length <= 8192 && value.none { it.code < 32 } &&
                (value.startsWith("https://") || value.startsWith("http://") ||
                    (value.isNotBlank() && !value.contains('/') && !value.contains('\\') && !value.contains("..")))
        }) { "映射快照字段无效" }
        return map
    }
}
