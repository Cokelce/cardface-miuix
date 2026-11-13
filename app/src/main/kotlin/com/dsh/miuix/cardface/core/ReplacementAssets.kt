package com.dsh.miuix.cardface.core

/** Permanent provenance of replacement material; independent from the currently enabled mapping. */
object ReplacementAssets {
    fun key(asset: String): String {
        val value = if (asset.startsWith("https://") || asset.startsWith("http://"))
            CardIdentity.key(asset) else asset.substringAfterLast('/')
        return CardRuntime.fingerprint(value)
    }

    fun isKnown(url: String, mappings: Map<String, String>, registryKeys: Set<String>, originKeys: Set<String>): Boolean {
        if (com.dsh.miuix.cardface.xposed.UrlMapper.isReplacementAsset(url, mappings)) return true
        if (key(url) in registryKeys) return true
        // Compatibility with older exact-value asset_origins history, without filename/host guessing.
        if (CardRuntime.fingerprint(url) in originKeys) return true
        if (!url.startsWith("https://") && !url.startsWith("http://") &&
            CardRuntime.fingerprint(url.substringAfterLast('/')) in originKeys) return true
        return false
    }
}
