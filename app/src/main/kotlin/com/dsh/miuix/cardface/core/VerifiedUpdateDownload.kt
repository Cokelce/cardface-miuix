package com.dsh.miuix.cardface.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

object VerifiedUpdateDownload {
    private const val TAG = "CardFace.Update"
    private const val MAX_REDIRECTS = 5
    private val lock = Any()

    suspend fun download(context: Context, release: Updates.Release, onProgress: (Int) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            synchronized(lock) { downloadLocked(context, release, onProgress) }
        }

    private fun downloadLocked(context: Context, release: Updates.Release, onProgress: (Int) -> Unit): File? {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val part = File(dir, "${UpdateArtifactPolicy.safeVersion(release.versionName)}-${UUID.randomUUID()}.part")
        var connection: HttpURLConnection? = null
        return try {
            var current = release.downloadUrl
            require(current.startsWith(Updates.DOWNLOAD_PREFIX))
            repeat(MAX_REDIRECTS + 1) { attempt ->
                val url = URL(current)
                require(url.protocol.equals("https", true))
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 30000
                    setRequestProperty("User-Agent", "CardFace-OnePlus")
                }
                val code = connection!!.responseCode
                if (code in 300..399) {
                    require(attempt < MAX_REDIRECTS)
                    current = connection!!.getHeaderField("Location") ?: error("redirect without location")
                    connection!!.disconnect(); connection = null
                } else {
                    require(code == HttpURLConnection.HTTP_OK)
                    val declared = connection!!.contentLengthLong
                    require(declared < 0L || UpdateArtifactPolicy.validateSize(declared, release.expectedSize))
                    connection!!.inputStream.use { input -> part.outputStream().use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            total += count; require(total <= UpdateArtifactPolicy.MAX_BYTES)
                            digest.update(buffer, 0, count); output.write(buffer, 0, count)
                            if (release.expectedSize > 0) onProgress((total * 100 / release.expectedSize).toInt().coerceIn(0, 100))
                        }
                        output.flush()
                        require(UpdateArtifactPolicy.validateSize(total, release.expectedSize))
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        require(release.sha256.isNullOrBlank() || actual.equals(release.sha256, true))
                        require(UpdateArtifactPolicy.validateZip(part))
                        val hash = actual
                        val final = File(dir, UpdateArtifactPolicy.finalName(release.versionName, hash))
                        if (!final.exists()) require(part.renameTo(final)) else part.delete()
                        return final
                    } }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "update download rejected: ${e.javaClass.simpleName}")
            part.delete(); null
        } finally { connection?.disconnect() }
    }

    @Suppress("unused")
    private fun installedSignatureMatches(context: Context, file: File): Boolean = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(file.path,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA) ?: return false
        info.packageName == context.packageName && Build.VERSION.SDK_INT >= 28 &&
            info.longVersionCode > context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode &&
            info.signingInfo?.apkContentsSigners?.isNotEmpty() == true
    }.getOrDefault(false)
}
