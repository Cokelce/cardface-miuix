package com.dsh.miuix.cardface.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.*

@RunWith(AndroidJUnit4::class)
class DeviceRegressionTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Isolated(base: Context) : ContextWrapper(base) {
        private val prefix = "qa-${java.util.UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val groups = linkedSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            groups += prefix + name
            return baseContext.getSharedPreferences(prefix + name, mode)
        }
        fun close() { groups.forEach { baseContext.deleteSharedPreferences(it) }; root.deleteRecursively() }
    }
    @Test fun backupRestoreRealPngPreservesUnrelatedCardsAndNames() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx); val store=ImageStore(ctx)
            val existing="https://wallet.example/existing.png"; prefs.setReplacement(existing,"https://cdn.example/old.png"); prefs.addCardUrl(listOf(existing))
            val png=File(ctx.cacheDir,"sample.png")
            val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            val original="https://wallet.example/bus.png"
            val meta=JSONObject().put("urls",JSONArray(listOf(original))).put("blacklist",JSONArray())
                .put("mappings",JSONObject().put(original,"sample.png"))
                .put("names",JSONObject().put(original,"手动公交卡"))
                .put("detected",JSONObject().put(original,"钱包公交卡"))
            val bytes=ByteArrayOutputStream(); BackupArchive.write(bytes,meta,mapOf("applied/sample.png" to png))
            val plan=BackupArchive.read(ByteArrayInputStream(bytes.toByteArray()),ctx.cacheDir)
            assertEquals(1,CardBackup.restore(ctx,prefs,store,plan))
            assertEquals("https://cdn.example/old.png",prefs.replacementFor(existing))
            assertTrue(prefs.allCardUrls().contains(existing))
            assertEquals("手动公交卡",prefs.cardFaces().first { it.url==original }.displayName)
            val name=prefs.replacementFor(original)!!
            assertTrue(name.startsWith("restored-")); assertTrue(store.find(name)!!.isFile)
            assertEquals("待加载",prefs.applicationState(original))
            assertFalse(plan.dir.exists())
        } finally {ctx.close()}
    }
    @Test fun invalidImageNeverPublishesMappings() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx);val store=ImageStore(ctx)
            val invalid=File(ctx.cacheDir,"bad.png").apply {writeText("not png")}
            val url="https://wallet.example/bad.png"
            val meta=JSONObject().put("urls",JSONArray(listOf(url))).put("blacklist",JSONArray())
                .put("mappings",JSONObject().put(url,"bad.png")).put("names",JSONObject()).put("detected",JSONObject())
            val bytes=ByteArrayOutputStream();BackupArchive.write(bytes,meta,mapOf("images/bad.png" to invalid))
            val plan=BackupArchive.read(ByteArrayInputStream(bytes.toByteArray()),ctx.cacheDir)
            try {CardBackup.restore(ctx,prefs,store,plan);fail("invalid image accepted")} catch (_:IllegalArgumentException) {}
            assertNull(prefs.replacementFor(url)); assertTrue(store.list().isEmpty());assertFalse(plan.dir.exists())
        } finally {ctx.close()}
    }
    @Test fun hookEvidenceMatchesOnlyCurrentSavedImage() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx);val url="https://wallet.example/state.png"
            prefs.setReplacement(url,"one.png")
            assertEquals("待加载",prefs.applicationState(url))
            val evidence=JSONObject().put("at",System.currentTimeMillis()+100).put("fingerprint",CardRuntime.fingerprint("one.png")).put("error","")
            ctx.getSharedPreferences("apply_state",0).edit().putString(url,evidence.toString()).commit()
            assertEquals("已命中",prefs.applicationState(url))
            prefs.setReplacement(url,"two.png")
            assertEquals("待加载",prefs.applicationState(url))
            prefs.setReplacement(url,null)
            assertEquals("未设置",prefs.applicationState(url))
        } finally {ctx.close()}
    }
    @Test fun exportAndImportUseActualDocumentStreams() {
        val ctx=Isolated(target)
        val document=File(target.filesDir,"updates/qa-backup.zip").apply {parentFile!!.mkdirs()}
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx); val store=ImageStore(ctx)
            val url="https://wallet.example/export.png"
            val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
            store.saveApplied(bitmap,"export.png"); bitmap.recycle()
            prefs.addCardUrl(listOf(url)); prefs.setReplacement(url,"export.png");prefs.setCustomName(url,"备份卡名")
            val uri=androidx.core.content.FileProvider.getUriForFile(target,"${target.packageName}.updates",document)
            assertEquals(1,CardBackup.export(ctx,prefs,store,uri))
            val plan=CardBackup.inspect(ctx,uri)
            assertEquals(1,plan.cardCount)
            assertEquals("备份卡名",plan.manifest.getJSONObject("names").getString(url))
            plan.close()
        } finally {document.delete();ctx.close()}
    }

    @Test fun fileProviderRestrictsPathsAndOwnArchiveIsParseable() {
        val file=File(target.filesDir,"updates/qa-check.apk").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf(1,2,3))}
        try {
            val uri=androidx.core.content.FileProvider.getUriForFile(target,"${target.packageName}.updates",file)
            target.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(byteArrayOf(1,2,3),it.readBytes()) }
            val outside=File(target.filesDir,"outside.apk")
            try {androidx.core.content.FileProvider.getUriForFile(target,"${target.packageName}.updates",outside);fail("outside exposed")} catch (_:IllegalArgumentException) {}
            val archive=target.packageManager.getPackageArchiveInfo(target.applicationInfo.sourceDir,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            assertEquals(target.packageName,archive!!.packageName)
            assertTrue(archive.signingInfo!!.apkContentsSigners.isNotEmpty())
        } finally {file.delete()}
    }
}
