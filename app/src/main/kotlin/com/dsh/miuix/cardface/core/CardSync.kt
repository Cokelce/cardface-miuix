package com.dsh.miuix.cardface.core

/**
 * 把图库里的替换图同步到卡包私有目录（`/data/data/<卡包>/files/images/`）。
 *
 * 为什么需要：hook 在卡包进程里没法直接读我们 App 的私有图片（content:// 在部分卡包版本里
 * 会被 Glide 拒绝或受包可见性影响）。原版就是「复制到卡包目录 + 返回 file://」，
 * 这条路径最稳。复制用 root（SukiSU），失败时 hook 会自动退回 content://。
 */
object CardSync {

    /** 映射快照：卡包 files/cardface.map，hook 冷启动直接读（无 IPC）。 */
    const val MAP_SNAPSHOT_NAME = "cardface.map"

    @Synchronized
    fun writeMapSnapshot(context: android.content.Context, prefs: AppPrefs): Boolean {
        val kind = WalletApps.primary(context) ?: return false
        var tmp: java.io.File? = null
        return try {
            val raw = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).all
            val mappings = raw.mapNotNull { (key, value) ->
                if ((key.startsWith("https://") || key.startsWith("http://")) && value is String && value.isNotBlank()) key to value else null
            }.toMap()
            val text = MappingSnapshot.encode(mappings, raw["mapping_revision"] as? Long ?: 0L)
            val file = java.io.File.createTempFile("cardface-map-", ".json", context.cacheDir)
            tmp = file
            java.io.FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            val dir = "/proc/1/root/data/data/${kind.packageName}/files"
            RootShell.copyIntoDir(file.absolutePath, dir, MAP_SNAPSHOT_NAME, timeoutMs = 10000).ok
        } catch (_: Throwable) { false }
        finally { tmp?.delete() }
    }


    /**
     * 卡包私有图片目录。
     *
     * 注意：SukiSU 的 su shell 有自己的 mount namespace，直接 `ls /data/data/<包名>` 会
     * 看不到卡包目录（只有 root 自己的几个包）；但 `/proc/1/root/data/data/<包名>` 是真实路径。
     * 所以复制操作统一走 `/proc/1/root` 前缀（在 su 里执行），hook 侧仍然用卡包自己的路径。
     */
    fun walletImagesDir(context: android.content.Context): String? {
        val kind = WalletApps.primary(context) ?: return null
        return "/proc/1/root/data/data/${kind.packageName}/files/images"
    }

    /** 复制单张图库图片到卡包目录；成功返回 true。 */
    fun copyImage(
        context: android.content.Context,
        store: ImageStore,
        name: String,
        timeoutMs: Long = 8000,
    ): Boolean {
        val dir = walletImagesDir(context) ?: return false
        val file = store.find(name) ?: return false
        if (!ImageValidation.valid(file)) return false
        val result = RootShell.copyIntoDir(file.absolutePath, dir, name, timeoutMs = timeoutMs)
        return result.ok
    }

    /**
     * 把所有「文件名」型映射同步一遍（启动时 / 重启卡包前调用）。
     * @return 成功复制的数量
     */
    fun syncAll(context: android.content.Context, prefs: AppPrefs, store: ImageStore): Int {
        val dir = walletImagesDir(context) ?: return 0
        var copied = 0
        prefs.mappedImageNames().forEach { name ->
            val file = store.find(name) ?: return@forEach
            if (!ImageValidation.valid(file)) return@forEach
            if (RootShell.copyIntoDir(file.absolutePath, dir, name).ok) copied++
        }
        writeMapSnapshot(context, prefs)
        return copied
    }
}
