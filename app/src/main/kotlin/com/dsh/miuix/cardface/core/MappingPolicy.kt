package com.dsh.miuix.cardface.core

/** Never infer that unrelated UUIDs, hosts or same-named files are the same card. */
object MappingPolicy {
    fun lookup(url: String, mappings: Map<String, String>): String? {
        mappings[url]?.takeIf { it.isNotBlank() }?.let { return it }
        val values = mappings.filterKeys { CardIdentity.key(it) == CardIdentity.key(url) }
            .values.filter { it.isNotBlank() }.distinct()
        return values.singleOrNull()
    }

    /** Exact mappings change independently; an unconfigured variant can inherit one unambiguous value. */
    fun mutationKeys(url: String, mappings: Map<String, String>): Set<String> {
        val aliases = mappings.filterKeys { CardIdentity.key(it) == CardIdentity.key(url) }
        if (aliases.values.distinct().size == 1) return aliases.keys + url
        return setOf(url)
    }

    data class State(val mappings: Map<String, String>, val revision: Long)
    fun select(provider: State?, framework: State?, snapshot: State?, cached: State?): State {
        // A readable provider is authoritative, including an intentionally empty map.
        if (provider != null) return provider
        // Ties prefer the last successful provider, then snapshot, then framework.
        return listOfNotNull(cached, snapshot, framework).maxByOrNull { it.revision } ?: State(emptyMap(), 0)
    }
}
