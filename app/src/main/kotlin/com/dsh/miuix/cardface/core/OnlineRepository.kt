package com.dsh.miuix.cardface.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class OnlineRepository(context: Context) {
    private val app = context.applicationContext
    private val cache = File(app.cacheDir,"online-catalog").apply { mkdirs() }
    private val downloads = File(app.filesDir,"online-originals").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("online_library",Context.MODE_PRIVATE)
    private val store = ImageStore(app)

    suspend fun load(category: OnlineCategory, page: Int, search: String, filter: String): OnlinePage {
        val url = OnlineCatalog.query(category,page,search,filter)
        val file = File(cache,CardRuntime.fingerprint(url)+".json")
        try {
            val text = requestText(url)
            val result = OnlineCatalog.parse(text,category)
            val part = File.createTempFile(".catalog-",".part",cache)
            try { part.writeText(text); check(part.renameTo(file)) } finally { part.delete() }
            trim(cache, 6L*1024*1024)
            return result
        } catch (cancel: CancellationException) { throw cancel }
        catch (t: Throwable) {
            if (file.isFile && file.length() <= 2*1024*1024 && System.currentTimeMillis()-file.lastModified()<7*86400000L) {
                return runCatching { OnlineCatalog.parse(file.readText(),category).copy(cached=true) }.getOrElse { throw t }
            }
            throw t
        }
    }
    private suspend fun requestText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout=12000;conn.readTimeout=15000
        conn.setRequestProperty("User-Agent","CardFace-OnePlus/1.0 (+https://github.com/Cokelce/cardface-miuix)")
        conn.setRequestProperty("Accept","application/json")
        try {
            require(conn.responseCode==200 && conn.url.protocol=="https" && conn.url.host=="hi.my-api.cn") { "目录请求失败（HTTP ${conn.responseCode}），可打开原网站" }
            val out=java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf=ByteArray(8192)
                while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buf);if(n<0)break
                    require(out.size()+n<=2*1024*1024) {"目录响应过大"};out.write(buf,0,n) }
            }
            return out.toString("UTF-8")
        } finally {conn.disconnect()}
    }
    suspend fun download(card: OnlineCard, progress: (Long,Long)->Unit): File {
        require(OnlineCatalog.validImageUrl(card.imageUrl)) {"图片地址不在已接入来源中"}
        val imageUrl=OnlineCatalog.normalizeImageUrl(card.imageUrl) ?: error("图片地址无效")
        val key="local:${CardRuntime.fingerprint(card.imageUrl)}"
        prefs.getString(key,null)?.let { name -> store.find(name)?.let { if(ImageValidation.valid(it)){rememberRecent(card);return it} } }
        val part=File.createTempFile(".download-",".part",downloads)
        try {
            val conn=URL(imageUrl).openConnection() as HttpURLConnection
            conn.connectTimeout=15000;conn.readTimeout=20000
            conn.setRequestProperty("User-Agent","CardFace-OnePlus")
            conn.setRequestProperty("Referer",card.category.webUrl)
            var done=0L
            try {
                require(conn.responseCode==200 && OnlineCatalog.validImageUrl(conn.url.toString())) {"图片下载失败或来源跳转异常"}
                val size=conn.contentLengthLong
                require(size<=16L*1024*1024) {"图片超过16MiB"}
                conn.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer=ByteArray(32768);var notified=0L
                    while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                        done+=n;require(done<=16L*1024*1024){"图片超过16MiB"};output.write(buffer,0,n)
                        if(done-notified>=256*1024){progress(done,size);notified=done}}
                } }
                require(done>0 && (size<0 || size==done)) {"图片下载不完整，请重试"}
            } finally {conn.disconnect()}
            currentCoroutineContext().ensureActive()
            val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(part.absolutePath,bounds)
            require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 && bounds.outWidth.toLong()*bounds.outHeight<=40_000_000) {"图片损坏或分辨率过大"}
            val bitmap=BitmapPipeline.decodeFile(part.absolutePath,2048) ?: error("图片解码失败")
            val name="online-${BackupArchive.sha(part)}.png"
            val file=try { store.find(name)?.takeIf{ImageValidation.valid(it)} ?: store.save(bitmap,name) } finally { bitmap.recycle() }
            require(prefs.edit().putString(key,name).commit()) {"本地图片索引保存失败"}
            rememberRecent(card)
            progress(done,done)
            return file
        } finally {part.delete()}
    }
    private fun encode(c:OnlineCard)=JSONObject().put("id",c.id).put("category",c.category.name).put("title",c.title)
        .put("subtitle",c.subtitle).put("image",c.imageUrl).put("source",c.source).toString()
    private fun decode(raw:String):OnlineCard?=runCatching {
        val j=JSONObject(raw);val image=j.getString("image");require(OnlineCatalog.validImageUrl(image))
        OnlineCard(j.getString("id"),OnlineCategory.valueOf(j.getString("category")),j.getString("title"),j.optString("subtitle"),image,j.optString("source"))
    }.getOrNull()
    fun favorites():List<OnlineCard> = prefs.all.filterKeys{it.startsWith("favorite:")}.values.mapNotNull{(it as? String)?.let(::decode)}.sortedBy{it.title}
    fun toggleFavorite(card:OnlineCard){val key="favorite:${card.key}";val e=prefs.edit();if(prefs.contains(key))e.remove(key)else{require(favorites().size<300){"收藏已达300张"};e.putString(key,encode(card))};check(e.commit())}
    fun isFavorite(card:OnlineCard)=prefs.contains("favorite:${card.key}")
    fun recent():List<OnlineCard> = prefs.all.filterKeys{it.startsWith("recent:")}.entries.sortedByDescending {
        prefs.getLong("time:${it.key.removePrefix("recent:")}",0)
    }.mapNotNull{(it.value as? String)?.let(::decode)}.take(30)
    private fun rememberRecent(card:OnlineCard){
        val e=prefs.edit().putString("recent:${card.key}",encode(card)).putLong("time:${card.key}",System.currentTimeMillis());e.commit()
        val keep=recent().map{it.key}.toSet();val cleanup=prefs.edit()
        prefs.all.keys.filter{it.startsWith("recent:") && it.removePrefix("recent:") !in keep}.forEach{cleanup.remove(it);cleanup.remove("time:${it.removePrefix("recent:")}")};cleanup.apply()
    }
    fun cachedLocal(card:OnlineCard):String?=prefs.getString("local:${CardRuntime.fingerprint(card.imageUrl)}",null)?.let{store.find(it)}?.let{Uri.fromFile(it).toString()}
    private fun trim(dir:File,limit:Long){var size=dir.listFiles()?.sumOf{it.length()}?:0;dir.listFiles()?.sortedBy{it.lastModified()}?.forEach{if(size>limit){val n=it.length();if(it.delete())size-=n}}}
}
