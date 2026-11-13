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
            val prefs=AppPrefs(ctx, syncSnapshots = false); val store=ImageStore(ctx)
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
            val prefs=AppPrefs(ctx, syncSnapshots = false);val store=ImageStore(ctx)
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
            val prefs=AppPrefs(ctx, syncSnapshots = false);val url="https://wallet.example/state.png"
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
            val prefs=AppPrefs(ctx, syncSnapshots = false); val store=ImageStore(ctx)
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
    @Test fun copyFailureKeepsOldMappingAndImage() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx,false);val store=ImageStore(ctx);val url="https://wallet.example/fail.png"
            prefs.setReplacement(url,"https://cdn.example/old.png")
            val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);store.saveApplied(bitmap,"new.png");bitmap.recycle()
            try {ReplacementService.applyWithSync(prefs,store,url,"new.png",{false},{true});fail("false success")} catch (_:IllegalStateException) {}
            assertEquals("https://cdn.example/old.png",prefs.replacementFor(url));assertTrue(store.find("new.png")!!.exists())
        } finally {ctx.close()}
    }
    @Test fun snapshotFailureRollsBackPreviousMapping() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx,false);val store=ImageStore(ctx);val url="https://wallet.example/snapshot.png"
            prefs.setReplacement(url,"https://cdn.example/old.png")
            var snapshots=0
            try {ReplacementService.applyWithSync(prefs,store,url,"https://cdn.example/new.png",{true},{++snapshots>1});fail("false success")} catch (_:IllegalStateException) {}
            assertEquals(2,snapshots);assertEquals("https://cdn.example/old.png",prefs.replacementFor(url))
        } finally {ctx.close()}
    }
    @Test fun clearingOneCardRetainsSharedImageAndOtherMapping() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx,false);val store=ImageStore(ctx);val a="https://wallet.example/a.png";val b="https://wallet.example/b.png"
            val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);store.saveApplied(bitmap,"shared.png");bitmap.recycle()
            prefs.setReplacement(a,"shared.png");prefs.setReplacement(b,"shared.png")
            ReplacementService.applyWithSync(prefs,store,a,null,{true},{true})
            assertNull(prefs.replacementFor(a));assertEquals("shared.png",prefs.replacementFor(b));assertTrue(ImageValidation.valid(store.find("shared.png")!!))
        } finally {ctx.close()}
    }
    @Test fun unrelatedUuidClearDoesNotTouchOtherCardOrAppearance() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").putInt("dark_mode",2).commit()
            val prefs=AppPrefs(ctx,false)
            val a="https://x/11111111-1111-1111-1111-111111111111/front.png"
            val b="https://x/22222222-2222-2222-2222-222222222222/front.png"
            prefs.setReplacement(a,"https://cdn.example/a.png");prefs.setReplacement(b,"https://cdn.example/b.png")
            prefs.setReplacement(a,null)
            assertEquals("https://cdn.example/b.png",prefs.replacementFor(b));assertNull(prefs.replacementFor(a))
            prefs.clearCardUrls();assertEquals(2,prefs.darkMode);assertEquals(SyncMode.LOCAL,prefs.syncMode)
        } finally {ctx.close()}
    }
    @Test fun stableSourceAliasClearRemovesInheritedMapping() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx,false);val a="https://x/old-uuid.png";val b="https://x/new-uuid.png"
            prefs.setReplacement(a,"https://cdn.example/a.png")
            ctx.getSharedPreferences("card_sources",0).edit().putString("stable-hash",JSONArray(listOf(a,b)).toString()).commit()
            assertEquals("https://cdn.example/a.png",prefs.replacementFor(b))
            prefs.setReplacement(b,null)
            assertNull(prefs.replacementFor(a));assertNull(prefs.replacementFor(b))
        } finally {ctx.close()}
    }
    @Test fun rootAtomicCopyRepairsBadDestinationAndPreservesOnFailure() {
        org.junit.Assume.assumeTrue("Root not available for isolated copy test",RootShell.isAvailable())
        val ctx=Isolated(target)
        try {
            val source=File(ctx.filesDir,"source.png").apply {writeText("complete-test-image")}
            val dir=File(ctx.filesDir,"wallet-test").apply {mkdirs()};val dest=File(dir,"card.png").apply {writeText("bad")}
            val repaired=RootShell.copyIntoDir(source.absolutePath,dir.absolutePath,"card.png")
            assertTrue("root repair: ${repaired.stderr}",repaired.ok);assertEquals(source.readText(),dest.readText())
            val failed=RootShell.copyIntoDir(File(ctx.filesDir,"missing").absolutePath,dir.absolutePath,"card.png")
            assertFalse(failed.ok);assertEquals(source.readText(),dest.readText())
            assertEquals(1,dir.listFiles()!!.size)
        } finally {ctx.close()}
    }

    @Test fun rootAtomicWalletLabelAndOwnerArePreserved() {
        org.junit.Assume.assumeTrue("Root not available",RootShell.isAvailable())
        val id=java.util.UUID.randomUUID().toString()
        val root="/data/local/tmp/cardface-qa-$id/data/data/mock.wallet"
        val source=File(target.cacheDir,"qa-source-$id.png").apply {writeText("full-image-copy-test")}
        fun q(s:String)="'"+s.replace("'","'\\''")+"'"
        try {
            val uid=android.os.Process.myUid()
            val setup=RootShell.exec("mkdir -p ${q(root)}/files && chown $uid:$uid ${q(root)} ${q(root)}/files")
            assertTrue(setup.ok)
            val result=RootShell.copyIntoDir(source.absolutePath,"$root/files/images","card.png")
            assertTrue("wallet copy: ${result.stderr}",result.ok)
            val read=RootShell.exec("cat ${q(root)}/files/images/card.png; stat -c %u ${q(root)}/files/images/card.png")
            assertTrue(read.ok);assertTrue(read.stdout.contains("full-image-copy-test"));assertTrue(read.stdout.contains(uid.toString()))
            RootShell.exec("printf bad > ${q(root)}/files/images/card.png")
            assertTrue(RootShell.copyIntoDir(source.absolutePath,"$root/files/images","card.png").ok)
        } finally {RootShell.exec("rm -rf ${q("/data/local/tmp/cardface-qa-$id")}");source.delete()}
    }
    @Test fun clearAllEquivalentVariantsDoesNotReviveMapping() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx,false);val a="https://x/card.png.-o.webp";val b="https://x/card.png.-q90.webp"
            prefs.setReplacement(a,"shared.png")
            prefs.settings.edit().putString(b,"shared.png").commit()
            prefs.setReplacement(a,null)
            assertNull(prefs.replacementFor(a));assertNull(prefs.replacementFor(b))
        } finally {ctx.close()}
    }

    @Test fun truncatedPngHeaderDoesNotPassImageValidation() {
        val ctx=Isolated(target)
        try {
            val file=File(ctx.filesDir,"test.png")
            val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            assertTrue(ImageValidation.valid(file))
            file.writeBytes(file.readBytes().take(33).toByteArray())
            assertFalse(ImageValidation.valid(file))
        } finally {ctx.close()}
    }

    @Test fun diagnoseCurrentSnapshotWithoutChangingMappings() {
        org.junit.Assume.assumeTrue("Explicit read-only-config snapshot verification only",
            InstrumentationRegistry.getArguments().getString("verifyCurrentSnapshot") == "true")
        org.junit.Assume.assumeTrue(RootShell.isAvailable())
        val ctx=target
        val result=CardSync.writeMapSnapshotDetailed(ctx,AppPrefs(ctx, false))
        println("SNAPSHOT_DIAG rc=${result.exitCode} timeout=${result.timedOut} error=${result.stderr.take(1200)}")
        assertTrue("snapshot failure: rc=${result.exitCode} ${result.stderr}",result.ok)
    }
    @Test fun allLogoCompositionsSaveAndValidate() {
        val ctx=Isolated(target)
        try {
            val src=Bitmap.createBitmap(960,606,Bitmap.Config.ARGB_8888)
            src.eraseColor(android.graphics.Color.LTGRAY)
            val store=ImageStore(ctx)
            for (logo in listOf(null,"jingjinji","shanghai","qvanchengtong","yinchuan")) {
                val result=BitmapPipeline.buildCard(target,src,opts=BitmapPipeline.Options(logoAsset=logo))
                val file=store.saveApplied(result,"logo-${logo ?: "none"}.png")
                assertTrue("logo=$logo",ImageValidation.valid(file))
                result.recycle()
            }
            src.recycle()
        } finally {ctx.close()}
    }

    @Test fun procRootWalletPathCopiesAndRecopiesWithExactLabel() {
        org.junit.Assume.assumeTrue(RootShell.isAvailable())
        val id=java.util.UUID.randomUUID().toString()
        val host="/data/local/tmp/cardface-proc-$id"
        val path="/proc/1/root$host/data/data/mock.wallet"
        val source=File(target.cacheDir,"qa-proc-source-$id.png").apply {writeText("complete test bytes")}
        fun q(s:String)="'"+s.replace("'","'\\''")+"'"
        try {
            val uid=android.os.Process.myUid()
            assertTrue(RootShell.exec("mkdir -p ${q(path)}/files && chown $uid:$uid ${q(path)} ${q(path)}/files").ok)
            for (iteration in 0..2) {
                if (iteration==2) source.writeText("changed source content")
                val result=RootShell.copyIntoDir(source.absolutePath,"$path/files/images","copy.png")
                assertTrue("proc copy $iteration rc=${result.exitCode} ${result.stderr}",result.ok)
                val check=RootShell.exec("[ \"\$(sha256sum ${q(source.absolutePath)} | cut -d ' ' -f 1)\" = \"\$(sha256sum ${q(path)}/files/images/copy.png | cut -d ' ' -f 1)\" ] && [ \"\$(stat -c %C ${q(path)})\" = \"\$(stat -c %C ${q(path)}/files/images/copy.png)\" ]")
                assertTrue("proc copied bytes/label $iteration ${check.stderr}",check.ok)
            }
        } finally {RootShell.exec("rm -rf ${q(host)}");source.delete()}
    }

    @Test fun clearReplacementRemovesOldMaterialAndPreventsRecapture() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx,false)
            val original="https://wallet.example/official.png";val custom="https://custom.example/old.png"
            prefs.addCardUrl(listOf(original,custom));prefs.setReplacement(original,custom)
            prefs.setReplacement(original,null)
            prefs.addCardUrl(listOf(custom,original))
            assertEquals(listOf(original),prefs.cardFaces().map{it.url})
            assertFalse(prefs.allCardUrls().contains(custom));assertNull(prefs.replacementFor(original))
            val reopened=AppPrefs(ctx,false)
            reopened.addCardUrl(listOf(custom))
            assertEquals(listOf(original),reopened.cardFaces().map{it.url})
        } finally {ctx.close()}
    }
    @Test fun legacyOriginsFilterAfterMappingWasAlreadyRemoved() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx,false)
            val original="https://wallet.example/official.png";val custom="https://custom.example/old.png"
            prefs.addCardUrl(listOf(original,custom))
            ctx.getSharedPreferences("asset_origins",0).edit().putStringSet(CardRuntime.fingerprint(custom),setOf(original)).commit()
            assertEquals(listOf(original),prefs.cardFaces().map{it.url})
            assertEquals(1,prefs.sanitizeCardUrls());assertEquals(listOf(original),prefs.allCardUrls())
        } finally {ctx.close()}
    }
    @Test fun explicitLegacyCleanupDoesNotTouchOfficialOrMappings() {
        val ctx=Isolated(target)
        try {
            ctx.getSharedPreferences("settings",0).edit().putString("sync_mode","local").commit()
            val prefs=AppPrefs(ctx,false)
            val original="https://wallet.example/boc.png";val custom="https://custom.example/old.png"
            prefs.addCardUrl(listOf(original,custom));prefs.setCustomName(original,"中国银行")
            prefs.discardReplacementEntry(custom)
            prefs.addCardUrl(listOf(custom))
            assertEquals(listOf(original),prefs.allCardUrls());assertEquals("中国银行",prefs.cardFaces().single().displayName)
            assertTrue(prefs.mappingPairs().isEmpty())
        } finally {ctx.close()}
    }
    @Test fun migrateUserConfirmedLegacyAssetOnly() {
        val hash=InstrumentationRegistry.getArguments().getString("confirmedLegacyAssetSha256")
        org.junit.Assume.assumeTrue("Explicit user-confirmed migration only",hash?.matches(Regex("[a-f0-9]{64}"))==true)
        val prefs=AppPrefs(target,false)
        val candidates=prefs.allCardUrls().filter {CardRuntime.fingerprint(it)==hash}
        assertTrue("ambiguous migration",candidates.size<=1)
        val before=prefs.mappingPairs()
        val originals=prefs.allCardUrls().filter {CardRuntime.fingerprint(it)!=hash}
        assertTrue("official originals missing", originals.any { java.net.URI(it).host=="fast-wallet.finz.oppomobile.com" })
        candidates.forEach {prefs.discardReplacementEntry(it)}
        assertEquals(before,prefs.mappingPairs())
        assertTrue(prefs.allCardUrls().containsAll(originals))
        assertTrue(prefs.allCardUrls().none {CardRuntime.fingerprint(it)==hash})
        assertTrue(target.getSharedPreferences("replacement_assets",0).all.isNotEmpty())
    }

}
