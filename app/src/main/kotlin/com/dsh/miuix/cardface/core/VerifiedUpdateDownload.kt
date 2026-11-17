package com.dsh.miuix.cardface.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

object VerifiedUpdateDownload {
    private val lock = Any()
    suspend fun download(context: Context, release: Updates.Release, onProgress: (Int) -> Unit = {}): File? =
        withContext(Dispatchers.IO) { synchronized(lock) { downloadLocked(context, release, onProgress) } }

    private fun downloadLocked(context: Context, release: Updates.Release, progress: (Int) -> Unit): File? {
        val dir = File(context.filesDir, "updates").apply { check(mkdirs() || isDirectory) }
        val part = File(dir, "download-${UUID.randomUUID()}.part")
        var connection: HttpURLConnection? = null
        return try {
            require(UpdateArtifactPolicy.safeVersion(release.versionName) != "unknown") { "更新版本号无效" }
            require(release.downloadUrl.startsWith(Updates.DOWNLOAD_PREFIX)) { "更新地址不属于本仓库" }
            var current = URL(release.downloadUrl)
            for (attempt in 0..5) {
                require(UpdateArtifactPolicy.allowedDownloadUrl(current.toString())) { "更新重定向地址无效" }
                connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "CardFace-OnePlus")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    require(attempt < 5) { "更新下载重定向过多" }
                    current = URL(current, connection.getHeaderField("Location") ?: error("重定向缺少地址"))
                    connection.disconnect()
                    connection = null
                    continue
                }
                require(code == HttpURLConnection.HTTP_OK) { "下载响应异常：$code" }
                val declared = connection.contentLengthLong
                require(declared < 0 || UpdateArtifactPolicy.validateSize(declared, release.expectedSize)) { "下载长度与发布包不一致" }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            require(total <= UpdateArtifactPolicy.MAX_BYTES) { "更新文件超出大小限制" }
                            digest.update(bytes, 0, count)
                            output.write(bytes, 0, count)
                            val expected = release.expectedSize.takeIf { it > 0 } ?: declared
                            if (expected > 0) progress((total * 100 / expected).toInt().coerceIn(0, 99))
                        }
                        output.fd.sync()
                    }
                }
                require(UpdateArtifactPolicy.validateSize(total, release.expectedSize) && (declared < 0 || total == declared)) { "更新下载未完整结束" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(release.sha256.isNullOrBlank() || actual.equals(release.sha256, true)) { "更新文件校验失败" }
                require(UpdateArtifactPolicy.validateZip(part)) { "下载文件不是完整APK" }
                require(verifyArchive(context, part, requireNewer = true)) { "更新包名、版本或签名不匹配" }
                val target = File(dir, "${release.versionName}-${actual}-${UUID.randomUUID()}.apk")
                check(part.renameTo(target)) { "更新文件保存失败" }
                progress(100)
                return target
            }
            error("更新下载重定向过多")
        } catch (e: Exception) {
            Log.w("CardFace.Update", "Download rejected: ${e.javaClass.simpleName}: ${e.message}")
            part.delete()
            null
        } finally { connection?.disconnect() }
    }

    @Suppress("DEPRECATION")
    fun verifyArchive(context: Context, file: File, requireNewer: Boolean): Boolean = runCatching {
        require(UpdateArtifactPolicy.validateSize(file.length(), 0) && UpdateArtifactPolicy.validateZip(file))
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val incoming = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(incoming.packageName == context.packageName)
        val nextVersion = if (Build.VERSION.SDK_INT >= 28) incoming.longVersionCode else incoming.versionCode.toLong()
        val oldVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(if (requireNewer) nextVersion > oldVersion else nextVersion >= oldVersion)
        fun signatures(info: android.content.pm.PackageInfo): Set<String> {
            val values = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return values.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val trusted = signatures(installed)
        require(trusted.isNotEmpty() && signatures(incoming) == trusted)
        true
    }.getOrDefault(false)
}
