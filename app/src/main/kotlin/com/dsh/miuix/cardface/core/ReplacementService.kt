package com.dsh.miuix.cardface.core

import android.content.Context

/** Called on IO: do not publish an unusable local image, and compensate config on snapshot failure. */
object ReplacementService {
    fun apply(context: Context, prefs: AppPrefs, store: ImageStore, url: String, value: String?) =
        applyWithSync(prefs, store, url, value,
            { CardSync.copyImage(context, store, it) }, { CardSync.writeMapSnapshot(context, prefs) })

    @Synchronized
    internal fun applyWithSync(prefs: AppPrefs, store: ImageStore, url: String, value: String?,
        copyImage: (String) -> Boolean, writeSnapshot: () -> Boolean) {
        if (!value.isNullOrBlank() && !value.startsWith("https://") && !value.startsWith("http://")) {
            val file = store.find(value) ?: error("替换图片不存在，原配置保持不变")
            check(ImageValidation.valid(file)) { "替换图片损坏，原配置保持不变" }
            check(copyImage(value)) { "图片同步失败，原配置保持不变；请检查 Root 与存储后重试" }
        }
        val old = prefs.mappingPairs()
        val keys = prefs.mutationKeys(url, old)
        try {
            prefs.setReplacement(url, value)
            check(writeSnapshot()) { "映射快照同步失败" }
        } catch (t: Throwable) {
            val editor = prefs.settings.edit()
            keys.forEach { key -> old[key]?.let { editor.putString(key, it) } ?: editor.remove(key) }
            editor.putLong("mapping_revision", maxOf(System.currentTimeMillis(), prefs.settings.getLong("mapping_revision", 0L) + 1))
            val restored = editor.commit()
            val snapshotRestored = writeSnapshot()
            prefs.bumpRevision()
            if (!restored || !snapshotRestored) throw IllegalStateException("保存未完成，回滚同步待确认；原图片保留，请重新同步", t)
            throw IllegalStateException("保存未完成，已恢复原映射；${t.message}", t)
        }
    }
}
