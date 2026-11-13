package com.dsh.miuix.cardface.core

/** Explicit source identities/asset history only; never guess card identity from a display name. */
object CardSourceIndex {
    fun stableId(card: Any?, wallet: String): String? {
        if (card == null) return null
        val id = runCatching { card.javaClass.getMethod("getUniqueCardId").invoke(card) as? String }.getOrNull()
            ?.trim()?.takeIf { it.length in 3..512 && !it.equals("null", true) } ?: return null
        return CardRuntime.fingerprint("$wallet:${card.javaClass.name}:$id")
    }

    fun expand(mappings: Map<String, String>, sourceGroups: Collection<Set<String>>): Map<String, String> {
        val additions = linkedMapOf<String, MutableSet<String>>()
        sourceGroups.forEach { urls ->
            val values = urls.mapNotNull { MappingPolicy.lookup(it, mappings) }.distinct()
            if (values.size == 1) urls.filter { it !in mappings }.forEach {
                additions.getOrPut(it) { linkedSetOf() }.add(values.single())
            }
        }
        return mappings + additions.filterValues { it.size == 1 }.mapValues { it.value.single() }
    }

    fun originalForAsset(asset: String, mappings: Map<String, String>, history: Set<String>): String? {
        val matches = mappings.filterValues { it == asset || (!asset.startsWith("http") && asset.substringAfterLast('/') == it) }.keys + history
        return matches.distinctBy { CardIdentity.key(it) }.singleOrNull()
    }
}
