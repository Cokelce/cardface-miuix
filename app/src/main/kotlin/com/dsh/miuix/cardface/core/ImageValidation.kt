package com.dsh.miuix.cardface.core

import android.graphics.BitmapFactory
import java.io.File

object ImageValidation {
    fun valid(file: File): Boolean = try {
        if (!file.isFile || file.length() !in 8..(16L * 1024 * 1024)) false
        else {
            val header = ByteArray(8)
            val signature = file.inputStream().use { it.read(header) == 8 && header.contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (signature) BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (!signature || bounds.outWidth !in 1..12000 || bounds.outHeight !in 1..12000 ||
                bounds.outWidth.toLong() * bounds.outHeight > 40_000_000) false
            else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 256)
                }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
                val valid = decoded != null
                decoded?.recycle()
                valid
            }
        }
    } catch (_: Throwable) { false }
}
