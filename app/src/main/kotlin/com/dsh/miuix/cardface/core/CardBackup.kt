package com.dsh.miuix.cardface.core

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CardBackup {
    fun export(context: Context, prefs: AppPrefs, store: ImageStore, uri: Uri): Int {
        val mappings = prefs.mappingPairs()
        val images = linkedMapOf<String, File>()
        store.dir.listFiles()?.filter { it.isFile && it.extension.equals("png", true) }?.forEach { images["images/${it.name}"] = it }
        mappings.values.filter { !it.startsWith("http://") && !it.startsWith("https://") }.distinct().forEach { name ->
            val file = store.find(name) ?: error("缺少替换图片：$name，请修复后重试")
            images["${if (file.parentFile == store.appliedDir) "applied" else "images"}/$name"] = file
        }
        fun strings(group: String): JSONObject = JSONObject().apply {
            context.getSharedPreferences(group, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                if (v is String && CardNameResolver.clean(v) != null) put(k, v)
            }
        }
        val meta = JSONObject().put("urls", JSONArray(prefs.allCardUrls()))
            .put("blacklist", JSONArray(prefs.blacklistedUrls().toList()))
            .put("mappings", JSONObject(mappings)).put("names", strings("card_names"))
            .put("detected", strings("detected_names")).put("createdAt", System.currentTimeMillis())
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("备份文件未打开")
        output.use { BackupArchive.write(it, meta, images) }
        return images.size
    }

    fun inspect(context: Context, uri: Uri): BackupArchive.Plan {
        val input = context.contentResolver.openInputStream(uri) ?: error("备份文件未打开")
        return BackupArchive.read(input, File(context.cacheDir, "backup-staging"))
    }

    /** Merge, never delete existing cards; same source key is restored from the selected backup. */
    fun restore(context: Context, prefs: AppPrefs, store: ImageStore, plan: BackupArchive.Plan): Int {
        val renamed = linkedMapOf<String, String>()
        val added = mutableListOf<File>()
        val groups = linkedMapOf(
            "settings" to prefs.settings, "card_urls" to prefs.urls,
            "saved_meta" to context.getSharedPreferences("saved_meta", Context.MODE_PRIVATE),
            "card_names" to context.getSharedPreferences("card_names", Context.MODE_PRIVATE),
            "detected_names" to context.getSharedPreferences("detected_names", Context.MODE_PRIVATE))
        val snapshots = groups.mapValues { it.value.all.toMap() }
        val touched = linkedMapOf<String, MutableSet<String>>()
        fun mark(group: String, key: String) { touched.getOrPut(group) { linkedSetOf() }.add(key) }
        try {
            // Validate every image before publishing any file or changing preferences.
            plan.images.values.forEach { file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "图片格式或分辨率无效" }
                file.inputStream().use { input ->
                    val header = ByteArray(8); require(input.read(header) == 8 && header.contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) { "备份图片不是 PNG" }
                }
            }
            plan.images.forEach { (path, source) ->
                val oldName = path.substringAfter('/')
                val hash = BackupArchive.sha(source)
                val newName = "restored-$hash.png"
                val targetDir = if (path.startsWith("applied/")) store.appliedDir else store.dir
                val target = File(targetDir, newName)
                if (target.exists()) require(BackupArchive.sha(target) == hash) { "已存在图片校验冲突" }
                else {
                    try { source.copyTo(target); added += target }
                    catch (t: Throwable) { target.delete(); throw t }
                }
                val previous = renamed.put(oldName, newName)
                require(previous == null || previous == newName) { "备份有同名异图冲突" }
            }
            val meta = plan.manifest
            val mappingEditor = prefs.settings.edit()
            val savedEditor = context.getSharedPreferences("saved_meta", Context.MODE_PRIVATE).edit()
            val now = System.currentTimeMillis()
            meta.getJSONObject("mappings").let { values -> values.keys().forEach { url ->
                val value = values.getString(url)
                mark("settings", url); mark("saved_meta", url)
                mappingEditor.putString(url, renamed[value] ?: value)
                savedEditor.putLong(url, now)
            } }
            mark("settings", "mapping_revision")
            mappingEditor.putLong("mapping_revision", maxOf(now, prefs.settings.getLong("mapping_revision", 0L) + 1))
            // Files are fully validated and published; each preference file uses an atomic commit.
            require(mappingEditor.commit()) { "写入替换配置失败" }
            require(savedEditor.commit()) { "写入保存时间失败" }
            val urls = meta.getJSONArray("urls")
            mark("card_urls", AppPrefs.KEY_ALL_CARD_URLS)
            val mergedUrls = prefs.allCardUrls().toSet() + (0 until urls.length()).map { urls.getString(it) }
            require(prefs.urls.edit().putStringSet(AppPrefs.KEY_ALL_CARD_URLS, mergedUrls).commit()) { "写入卡面地址失败" }
            val black = meta.getJSONArray("blacklist")
            val allBlack = prefs.blacklistedUrls() + (0 until black.length()).map { black.getString(it) }
            mark("card_urls", AppPrefs.KEY_BLACK_CARD_URLS)
            require(prefs.urls.edit().putStringSet(AppPrefs.KEY_BLACK_CARD_URLS, allBlack).commit()) { "写入黑名单失败" }
            for ((backupGroup, prefGroup) in listOf("names" to "card_names", "detected" to "detected_names")) {
                val editor = context.getSharedPreferences(prefGroup, Context.MODE_PRIVATE).edit()
                meta.getJSONObject(backupGroup).let { values -> values.keys().forEach { mark(prefGroup, it); editor.putString(it, values.getString(it)) } }
                require(editor.commit()) { "写入卡名失败" }
            }
            prefs.bumpRevision()
            return plan.cardCount
        } catch (t: Throwable) {
            // Compensate touched keys only; never clear unrelated live data.
            var rollbackOk = true
            touched.forEach { (group, keys) ->
                val editor = groups.getValue(group).edit()
                keys.forEach { key ->
                    when (val value = snapshots.getValue(group)[key]) {
                        null -> editor.remove(key)
                        is String -> editor.putString(key, value)
                        is Long -> editor.putLong(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> rollbackOk = false
                    }
                }
                rollbackOk = editor.commit() && rollbackOk
            }
            if (rollbackOk && prefs.mappingPairs().values.none { it in renamed.values }) added.forEach { it.delete() }
            if (!rollbackOk) throw IllegalStateException("恢复存储异常，部分回滚未确认；图片已保留，请重试恢复", t)
            throw t
        } finally { plan.close() }
    }
}
