package com.dsh.miuix.cardface.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateDownloader {
    suspend fun download(context: Context, release: Updates.Release, progress: (Long, Long) -> Unit): File {
        require(release.sha256 != null) { "发布信息缺少 SHA-256，请在 Release 页面核对，暂不自动安装" }
        require(release.size in 1..(128L * 1024 * 1024)) { "APK 大小信息无效" }
        require(release.downloadUrl.startsWith("https://github.com/${Updates.REPOSITORY}/releases/download/")) { "下载地址不属于本项目" }
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val part = File(dir, "download-${java.util.UUID.randomUUID()}.part")
        try {
            val conn = URL(release.downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "cardface-miuix-oneplus")
            conn.instanceFollowRedirects = true
            var size = 0L
            try {
                require(conn.responseCode == 200 && conn.url.protocol == "https") { "下载响应异常" }
                conn.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var lastProgress = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        size += n; require(size <= release.size) { "下载超出预期大小" }
                        output.write(buffer, 0, n)
                        if (size - lastProgress >= 256 * 1024) { progress(size, release.size); lastProgress = size }
                    }
                } }
            } finally { conn.disconnect() }
            UpdateIntegrity.check(release.size, size, release.sha256, BackupArchive.sha(part))
            validateApk(context, part, release.versionName)
            currentCoroutineContext().ensureActive()
            val dest = File(dir, "CardFace-${release.versionName}.apk")
            // Same-version retry reuses only an identical, revalidated APK.
            if (dest.exists()) {
                require(BackupArchive.sha(dest) == release.sha256) { "已存在同版本文件不一致" }
                part.delete()
            } else check(part.renameTo(dest)) { "保存安装包失败" }
            dir.listFiles()?.filter { it != dest && it.extension == "apk" }?.forEach { it.delete() }
            progress(size, release.size)
            return dest
        } catch (t: Throwable) { part.delete(); throw t }
    }

    @Suppress("DEPRECATION")
    fun validateApk(context: Context, file: File, expectedVersion: String) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("APK 解析失败")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "安装包名与当前应用不一致" }
        require(archive.versionName == expectedVersion) { "安装包版本与发布标签不一致" }
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(newCode > oldCode) { "安装包版本号未递增" }
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "本机 Android 版本低于安装要求" }
        fun signatures(info: android.content.pm.PackageInfo): Set<String> {
            val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return sigs.orEmpty().map { CardRuntime.fingerprint(it.toCharsString()) }.toSet()
        }
        require(UpdateIntegrity.sameSigners(signatures(installed), signatures(archive))) { "APK 签名与已安装版本不一致，已停止安装" }
    }

    fun install(context: Context, file: File) {
        require(file.canonicalFile.parentFile == File(context.filesDir, "updates").canonicalFile && file.isFile) { "安装包路径无效" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
