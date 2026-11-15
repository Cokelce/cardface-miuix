package com.dsh.miuix.cardface.core.catalog

import android.content.Context
import android.graphics.BitmapFactory
import com.dsh.miuix.cardface.core.BitmapPipeline
import com.dsh.miuix.cardface.core.ImageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 卡面库的缓存、下载与收藏/最近记录。
 *
 * 约束：目录/图片都只读第三方来源，不镜像站点；图片必须下载到本地、校验后再交给图库，
 * 绝不把远程 URL 直接交给卡包进程。
 */
class CatalogRepository(context: Context) {

    private val app = context.applicationContext
    private val cacheDir = File(app.cacheDir, "catalog").apply { mkdirs() }
    private val downloadDir = File(app.cacheDir, "catalog-download").apply { mkdirs() }
    private val store = ImageStore(app)
    private val prefs = app.getSharedPreferences("catalog_library", Context.MODE_PRIVATE)
    private val animeIndexFile = File(cacheDir, "anime-index.json")
    @Volatile
    private var animeItems: List<CatalogItem>? = null
    private val animeLock = Any()

    // ---------- 目录 ----------

    suspend fun load(category: CatalogCategory, page: Int, search: String, filter: String): CatalogPage {
        if (category == CatalogCategory.ANIME) return loadAnime(page, search, filter)
        val primary = loadQuery(
            HibankCatalogSource.queryUrl(category, page, search, filter),
            category,
        )
        // 搜索词在“卡面名”里找不到时，再按分类的第二个字段（银行/城市/IP 名）找一次。
        if (primary.items.isEmpty() && search.isNotBlank()) {
            val altUrl = HibankCatalogSource.queryUrl(
                category, page, search, filter,
                searchKeyOverride = category.alternateSearchKey,
            )
            runCatching { loadQuery(altUrl, category) }
                .getOrNull()
                ?.takeIf { it.items.isNotEmpty() }
                ?.let { return it }
        }
        return primary
    }

    private suspend fun loadQuery(url: String, category: CatalogCategory): CatalogPage {
        val cacheFile = File(cacheDir, HibankCatalogSource.sha1(url).take(20) + ".json")
        return try {
            val text = requestText(url)
            val parsed = HibankCatalogSource.parse(text, category)
            runCatching {
                val tmp = File.createTempFile(".catalog-", ".part", cacheDir)
                try {
                    tmp.writeText(text)
                    if (!tmp.renameTo(cacheFile)) tmp.copyTo(cacheFile, overwrite = true)
                } finally {
                    tmp.delete()
                }
                trimFolder(cacheDir, 6L * 1024 * 1024)
            }
            parsed
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val cached = cacheFile.takeIf {
                it.isFile && it.length() in 1..(2L * 1024 * 1024) &&
                    System.currentTimeMillis() - it.lastModified() < 7L * 24 * 3600 * 1000
            }?.let { file ->
                runCatching { HibankCatalogSource.parse(file.readText(), category).copy(cached = true) }
                    .getOrNull()
            }
            cached ?: throw t
        }
    }

    private suspend fun requestText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "CardFace/1.0 (+https://github.com/MiamBao/cardface-miuix)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            require(conn.responseCode == 200) { "目录请求失败（HTTP ${conn.responseCode}）" }
            require(conn.url.protocol == "https" && conn.url.host == "hi.my-api.cn") { "目录来源异常" }
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    require(out.size() + n <= 2 * 1024 * 1024) { "目录响应过大" }
                    out.write(buf, 0, n)
                }
            }
            return out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 图片下载 ----------

    /** 下载并校验图片，存进图库后返回本地文件；失败抛异常并清理临时文件。 */
    suspend fun download(item: CatalogItem, onProgress: (Long, Long) -> Unit): File {
        val imageUrl = HibankCatalogSource.normalizeImageUrl(item.imageUrl)
            ?: error("图片地址不在已接入来源中")
        val localKey = "local:" + HibankCatalogSource.sha1(imageUrl)
        prefs.getString(localKey, null)?.let { name ->
            store.find(name)?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        }

        val part = File.createTempFile(".download-", ".part", downloadDir)
        try {
            val conn = URL(imageUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "CardFace/1.0")
            conn.setRequestProperty("Referer", item.category.webUrl)
            var done = 0L
            try {
                require(conn.responseCode == 200) { "图片下载失败（HTTP ${conn.responseCode}）" }
                require(conn.url.protocol == "https" && conn.url.host == "storage.my-api.cn") { "图片来源跳转异常" }
                val size = conn.contentLengthLong
                require(size <= 16L * 1024 * 1024) { "图片超过 16MiB" }
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(32 * 1024)
                        var notified = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            require(done <= 16L * 1024 * 1024) { "图片超过 16MiB" }
                            output.write(buf, 0, n)
                            if (done - notified >= 256 * 1024) {
                                onProgress(done, size)
                                notified = done
                            }
                        }
                    }
                }
                require(done > 0 && (size < 0 || size == done)) { "图片下载不完整，请重试" }
            } finally {
                conn.disconnect()
            }
            currentCoroutineContext().ensureActive()

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(part.absolutePath, bounds)
            require(
                bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000,
            ) { "图片损坏或分辨率过大" }

            val bitmap = BitmapPipeline.decodeFile(part.absolutePath, 2048) ?: error("图片解码失败")
            val name = "lib-" + HibankCatalogSource.sha1(imageUrl).take(16) + ".png"
            val file = try {
                store.find(name) ?: store.save(bitmap, name)
            } finally {
                bitmap.recycle()
            }
            require(prefs.edit().putString(localKey, name).commit()) { "本地图片索引保存失败" }
            rememberRecent(item)
            onProgress(done, done)
            return file
        } finally {
            part.delete()
        }
    }

    /** 已下载过就返回本地文件（网格直接显示本地图，省流量）。 */
    fun localImage(item: CatalogItem): File? =
        prefs.getString("local:" + HibankCatalogSource.sha1(item.imageUrl), null)
            ?.let { store.find(it) }

    // ---------- 收藏 / 最近 ----------

    fun favorites(): List<CatalogItem> = prefs.all
        .filterKeys { it.startsWith("favorite:") }
        .values
        .mapNotNull { (it as? String)?.let(::decode) }
        .sortedBy { it.title }

    fun isFavorite(item: CatalogItem): Boolean = prefs.contains("favorite:${item.key}")

    fun toggleFavorite(item: CatalogItem) {
        val key = "favorite:${item.key}"
        val editor = prefs.edit()
        if (prefs.contains(key)) {
            editor.remove(key)
        } else {
            require(favorites().size < 300) { "收藏已达 300 张" }
            editor.putString(key, encode(item))
        }
        check(editor.commit()) { "收藏保存失败" }
    }

    fun recents(): List<CatalogItem> = prefs.all
        .filterKeys { it.startsWith("recent:") }
        .entries
        .sortedByDescending { prefs.getLong("time:${it.key.removePrefix("recent:")}", 0) }
        .mapNotNull { (it.value as? String)?.let(::decode) }
        .take(30)

    private fun rememberRecent(item: CatalogItem) {
        prefs.edit()
            .putString("recent:${item.key}", encode(item))
            .putLong("time:${item.key}", System.currentTimeMillis())
            .apply()
    }

    // ---------- 二次元 IP（从 IP 联动目录复制一份，客户端筛选） ----------

    private suspend fun loadAnime(page: Int, search: String, filter: String): CatalogPage {
        var list = ensureAnimeItems()
        if (search.isNotBlank()) {
            val q = search.trim()
            list = list.filter {
                it.title.contains(q, ignoreCase = true) || it.subtitle.contains(q, ignoreCase = true)
            }
        }
        if (filter.isNotBlank()) {
            val q = filter.trim()
            list = list.filter { it.subtitle.contains(q, ignoreCase = true) }
        }
        list = list.sortedWith(compareBy({ it.subtitle }, { it.title }))
        val pageSize = 24
        val pages = maxOf(1, (list.size + pageSize - 1) / pageSize)
        val p = page.coerceIn(1, pages)
        return CatalogPage(
            items = list.drop((p - 1) * pageSize).take(pageSize),
            page = p,
            pages = pages,
            total = list.size,
        )
    }

    private suspend fun ensureAnimeItems(): List<CatalogItem> {
        animeItems?.let { return it }
        synchronized(animeLock) { animeItems?.let { return it } }
        loadAnimeCache()?.let {
            animeItems = it
            return it
        }
        val fetched = fetchAnimeIndex()
        animeItems = fetched
        runCatching { saveAnimeCache(fetched) }
        return fetched
    }

    private fun loadAnimeCache(): List<CatalogItem>? {
        if (!animeIndexFile.isFile) return null
        if (System.currentTimeMillis() - animeIndexFile.lastModified() > 7L * 24 * 3600 * 1000) return null
        return runCatching {
            val array = org.json.JSONArray(animeIndexFile.readText())
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }?.let(::decode)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun saveAnimeCache(items: List<CatalogItem>) {
        val array = org.json.JSONArray()
        items.forEach { array.put(encode(it)) }
        val tmp = File.createTempFile(".anime-", ".part", cacheDir)
        try {
            tmp.writeText(array.toString())
            if (!tmp.renameTo(animeIndexFile)) tmp.copyTo(animeIndexFile, overwrite = true)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun fetchAnimeIndex(): List<CatalogItem> = coroutineScope {
        val first = requestText(animePageUrl(1))
        val totalPages = runCatching {
            JSONObject(first).optJSONObject("data")?.optInt("total_pages", 16) ?: 16
        }.getOrDefault(16).coerceIn(1, 64)
        val semaphore = Semaphore(4)
        val rest = (2..totalPages).map { p ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    runCatching { requestText(animePageUrl(p)) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
        (listOf(first) + rest)
            .flatMap { text ->
                runCatching { HibankCatalogSource.parse(text, CatalogCategory.IP).items }
                    .getOrDefault(emptyList())
            }
            .distinctBy { it.key }
            .filter { AnimeIps.isAnime(it.subtitle) }
    }

    private fun animePageUrl(page: Int): String =
        "https://hi.my-api.cn/api/?resource=face_ctu_origin&page=$page&page_size=100"

    private fun encode(item: CatalogItem): String = JSONObject()
        .put("id", item.id)
        .put("category", item.category.name)
        .put("title", item.title)
        .put("subtitle", item.subtitle)
        .put("image", item.imageUrl)
        .put("source", item.source)
        .toString()

    private fun decode(raw: String): CatalogItem? = runCatching {
        val json = JSONObject(raw)
        val image = json.getString("image")
        require(HibankCatalogSource.validImageUrl(image))
        CatalogItem(
            id = json.getString("id"),
            category = CatalogCategory.valueOf(json.getString("category")),
            title = json.getString("title"),
            subtitle = json.optString("subtitle"),
            imageUrl = image,
            source = json.optString("source"),
        )
    }.getOrNull()

    private fun trimFolder(dir: File, limit: Long) {
        var size = dir.listFiles()?.sumOf { it.length() } ?: 0
        dir.listFiles()?.sortedBy { it.lastModified() }?.forEach {
            if (size > limit) {
                val n = it.length()
                if (it.delete()) size -= n
            }
        }
    }
}
