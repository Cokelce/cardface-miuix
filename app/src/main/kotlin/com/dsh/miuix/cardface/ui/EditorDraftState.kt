package com.dsh.miuix.cardface.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Bundle stores bounded metadata; immutable bitmap snapshots live in the private cache. */
object EditorDraftState {
    private val bitmapLock = kotlinx.coroutines.sync.Mutex()
    private val MAX_DRAFT_FILES = 8
    private const val PREFIX = "cardface.draft."
    private const val BITMAP_FILE = "editor-draft.png"
    private const val LEGACY_BITMAP_COMPAT = false
    const val MAX_PNG_BYTES = 12L * 1024L * 1024L

    /** Publish a unique snapshot atomically; keep the previous recoverable state until publication. */
    suspend fun saveBitmap(cacheDir: File, bitmap: Bitmap?, url: String, previousPath: String? = null): String? = withContext(Dispatchers.IO) {
        bitmapLock.lock()
        try {
            if (bitmap == null || bitmap.isRecycled || bitmap.width.toLong() * bitmap.height > 32_000_000) return@withContext null
            val hash = DraftValidation.urlHash(url)
            val name = "editor-draft-$hash-${java.util.UUID.randomUUID()}.png"
            val target = File(cacheDir, name)
            val temp = File(cacheDir, "$name.tmp")
            try {
                FileOutputStream(temp).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) error("PNG encoding failed")
                    output.fd.sync()
                }
                if (temp.length() !in 1..MAX_PNG_BYTES || !temp.renameTo(target)) {
                    temp.delete()
                    return@withContext null
                }
                val keep = setOf(target.canonicalFile, previousPath?.let { runCatching { File(it).canonicalFile }.getOrNull() })
                    .filterNotNull().toSet()
                val snapshots = cacheDir.listFiles { file -> file.name.matches(Regex("editor-draft-[0-9a-f]{24}-[0-9a-fA-F-]{36}\\.png")) }?.toList().orEmpty()
                snapshots.filter { it.canonicalFile !in keep }.sortedBy { it.lastModified() }
                    .take((snapshots.size - MAX_DRAFT_FILES).coerceAtLeast(0)).forEach(File::delete)
                target.absolutePath
            } catch (_: Throwable) {
                temp.delete()
                target.delete()
                null
            }
        } finally { bitmapLock.unlock() }
    }

    /** Call from a background coroutine, then assign the returned bitmap to draft.localBitmap. */
    suspend fun restoreBitmap(path: String?): Bitmap? = withContext(Dispatchers.IO) {
        bitmapLock.lock()
        try {
            val file = path?.let(::File) ?: return@withContext null
            if (!file.isFile || file.length() !in 1..MAX_PNG_BYTES) return@withContext null
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth.toLong() * options.outHeight > 32_000_000) return@withContext null
            runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        } finally { bitmapLock.unlock() }
    }

    /** Main-thread safe: writes only strings/numbers/booleans and schedules no bitmap compression. */
    fun saveScalars(out: Bundle, model: AppModel) {
        val draft = model.editor
        out.putString(PREFIX + "url", draft.url)
        out.putString(PREFIX + "bitmapPath", model.draftBitmapPath.takeIf {
            draft.restoringBitmap || (draft.localBitmap != null && draft.localBitmap === model.persistedDraftBitmap)
        })
        out.putString(PREFIX + "localUri", draft.localUri?.toString())
        out.putString(PREFIX + "localCrop", draft.localCrop?.let { listOf(it.left, it.top, it.right, it.bottom).joinToString(",") })
        out.putString(PREFIX + "logoAsset", draft.logoAsset)
        out.putBoolean(PREFIX + "roundCorners", draft.roundCorners)
        out.putString(PREFIX + "cropUri", model.cropUri?.toString())
        out.putString(PREFIX + "target", model.cropTarget.name)
        out.putStringArrayList(PREFIX + "cropQueue", ArrayList(model.savedCropQueue()))
        out.putInt(PREFIX + "cropBatchTotal", model.cropBatchTotal)
        out.putInt(PREFIX + "cropBatchIndex", model.cropBatchIndex)
    }

    /** Restore scalar fields after CardFaceApp has created its AppModel. Bitmap restoration is async. */
    fun restoreScalars(state: Bundle, model: AppModel): String? {
        state.getString(PREFIX + "url")?.let(model::openEditor)
        val draft = model.editor
        draft.localUri = state.getString(PREFIX + "localUri")?.takeIf(DraftValidation::validQueueUri)?.let(Uri::parse)
        draft.localCrop = DraftValidation.parseCrop(state.getString(PREFIX + "localCrop"))?.let { RectF(it[0], it[1], it[2], it[3]) }
        draft.logoAsset = state.getString(PREFIX + "logoAsset")
        draft.roundCorners = state.getBoolean(PREFIX + "roundCorners", true)
        model.cropUri = state.getString(PREFIX + "cropUri")?.takeIf(DraftValidation::validQueueUri)?.let(Uri::parse)
        model.cropTarget = runCatching { CropTarget.valueOf(state.getString(PREFIX + "target") ?: "Editor") }
            .getOrDefault(CropTarget.Editor)
        model.restoreCropQueue(DraftValidation.boundedQueue(state.getStringArrayList(PREFIX + "cropQueue").orEmpty()),
            state.getInt(PREFIX + "cropBatchTotal"), state.getInt(PREFIX + "cropBatchIndex"))
        val savedUrl = state.getString(PREFIX + "url").orEmpty()
        val validPath = runCatching { state.getString(PREFIX + "bitmapPath")?.takeIf { path ->
            val file = File(path)
            val snapshotMatches = DraftValidation.snapshotNameMatches(file.name, savedUrl)
            val legacyMatches = LEGACY_BITMAP_COMPAT && savedUrl.isNotBlank() && file.name == BITMAP_FILE && state.getString(PREFIX + "url") == savedUrl
            (snapshotMatches || legacyMatches) && file.parentFile?.canonicalFile == model.context.cacheDir.canonicalFile &&
                file.isFile && file.length() in 1..MAX_PNG_BYTES
        }
        }.getOrNull()
        model.draftBitmapPath = validPath
        draft.restoringBitmap = validPath != null
        return validPath
    }
}
