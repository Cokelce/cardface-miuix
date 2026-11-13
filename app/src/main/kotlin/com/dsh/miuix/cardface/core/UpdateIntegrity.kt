package com.dsh.miuix.cardface.core

object UpdateIntegrity {
    fun sha256(digest: String?, notes: String = ""): String? {
        val normalized = digest.orEmpty().removePrefix("sha256:").trim()
        if (normalized.matches(Regex("[a-fA-F0-9]{64}"))) return normalized.lowercase()
        return Regex("(?i)SHA-?256\\s*[:：]\\s*`?([a-f0-9]{64})").find(notes)?.groupValues?.get(1)?.lowercase()
    }
    fun check(expectedSize: Long, actualSize: Long, expectedHash: String?, actualHash: String) {
        require(expectedSize in 1..(128L * 1024 * 1024) && expectedSize == actualSize) { "APK 大小与发布信息不一致" }
        require(expectedHash != null && expectedHash == actualHash.lowercase()) { "SHA-256 校验失败或发布信息缺少校验值" }
    }
    fun sameSigners(installed: Set<String>, archive: Set<String>): Boolean = installed.isNotEmpty() && installed == archive
}
