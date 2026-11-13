package com.dsh.miuix.cardface.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.zip.*

/** Versioned ZIP; all entries and checksums are validated before changing live data. */
object BackupArchive {
    const val MAX_TOTAL = 128L * 1024 * 1024
    const val MAX_FILE = 16L * 1024 * 1024
    const val MAX_ENTRIES = 512
    private val filename = Regex("^[^/\\\\:\\p{Cc}]{1,180}\\.png$", RegexOption.IGNORE_CASE)
    fun validPath(path: String): Boolean {
        if (path == "manifest.json") return true
        val parts = path.split('/')
        return parts.size == 2 && parts[0] in setOf("images", "applied") &&
            filename.matches(parts[1]) && !parts[1].contains("..")
    }
    data class Plan(val dir: File, val manifest: JSONObject, val images: Map<String, File>) : Closeable {
        val cardCount get() = manifest.getJSONArray("urls").length()
        override fun close() { dir.deleteRecursively() }
    }

    fun write(output: OutputStream, metadata: JSONObject, images: Map<String, File>) {
        require(images.size <= MAX_ENTRIES - 1) { "图片超过数量上限" }
        var total = 0L
        val hashes = JSONObject()
        images.forEach { (path, file) ->
            require(validPath(path) && path != "manifest.json" && file.isFile && file.length() in 1..MAX_FILE) { "图片条目无效：$path" }
            total += file.length(); require(total <= MAX_TOTAL) { "备份超过128MB" }
            hashes.put(path, JSONObject().put("sha256", sha(file)).put("size", file.length()))
        }
        metadata.put("format", "cardface-backup").put("schema", 1).put("images", hashes)
        validateMetadata(metadata)
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            val json = metadata.toString().toByteArray(Charsets.UTF_8)
            require(json.size <= 2 * 1024 * 1024) { "备份索引过大" }
            zip.write(json); zip.closeEntry()
            images.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, stagingParent: File): Plan {
        stagingParent.mkdirs()
        val dir = File(stagingParent, "restore-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val entries = LinkedHashMap<String, File>()
            var total = 0L
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    require(!e.isDirectory && validPath(e.name) && !entries.containsKey(e.name)) { "备份含无效或重复路径" }
                    require(entries.size < MAX_ENTRIES) { "备份条目过多" }
                    val limit = if (e.name == "manifest.json") 2L * 1024 * 1024 else MAX_FILE
                    val file = File(dir, e.name).apply { parentFile!!.mkdirs() }
                    var size = 0L
                    file.outputStream().use { out ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            size += n; total += n
                            require(size <= limit && total <= MAX_TOTAL + 2 * 1024 * 1024) { "备份解压大小超限" }
                            out.write(buffer, 0, n)
                        }
                    }
                    entries[e.name] = file; zip.closeEntry()
                }
            }
            val manifest = JSONObject(entries.remove("manifest.json")?.readText() ?: error("缺少备份索引"))
            validateMetadata(manifest)
            val hashes = manifest.getJSONObject("images")
            require(hashes.keySet() == entries.keys) { "图片清单与文件不一致" }
            entries.forEach { (path, file) ->
                val record = hashes.getJSONObject(path)
                require(file.length() == record.getLong("size") && sha(file) == record.getString("sha256")) { "图片校验失败：$path" }
            }
            val imageNames = entries.keys.map { it.substringAfter('/') }.toSet()
            manifest.getJSONObject("mappings").let { mappings -> mappings.keys().forEach { key ->
                val value = mappings.getString(key)
                require(value.startsWith("https://") || value.startsWith("http://") || value in imageNames) { "替换图片缺失" }
            } }
            return Plan(dir, manifest, entries)
        } catch (t: Throwable) { dir.deleteRecursively(); throw t }
    }

    fun validateMetadata(meta: JSONObject) {
        require(meta.optString("format") == "cardface-backup" && meta.optInt("schema") == 1) { "备份格式或版本不匹配" }
        fun validUrl(value: String) = value.length in 1..8192 && (value.startsWith("https://") || value.startsWith("http://")) && value.none { it.code < 32 }
        for (group in listOf("urls", "blacklist")) {
            val values = meta.getJSONArray(group); require(values.length() <= 5000) { "卡片条目过多" }
            for (i in 0 until values.length()) require(validUrl(values.getString(i))) { "卡面地址格式错误" }
        }
        for (group in listOf("mappings", "names", "detected")) {
            val values = meta.getJSONObject(group); require(values.length() <= 5000) { "映射条目过多" }
            values.keys().forEach { key ->
                require(validUrl(key)) { "映射键格式错误" }
                val value = values.getString(key)
                if (group == "mappings") require(validUrl(value) || (filename.matches(value) && !value.contains(".."))) { "替换值格式错误" }
                else require(CardNameResolver.clean(value) != null) { "卡名格式错误" }
            }
        }
    }

    fun sha(file: File): String = file.inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32768)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
}
