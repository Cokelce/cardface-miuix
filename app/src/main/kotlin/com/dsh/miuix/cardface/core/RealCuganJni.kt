package com.dsh.miuix.cardface.core

import android.graphics.Bitmap

/** Real-CUGAN-SE 的 JNI 入口（CPU，不依赖解压可执行文件）。 */
object RealCuganJni {

    private var loaded = false

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("realcuganjni")
            loaded = true
            true
        } catch (t: Throwable) {
            android.util.Log.e("SRTEST", "load realcuganjni failed", t)
            false
        }
    }

    /** 2x 超分；失败返回 null。 */
    fun upscale2x(paramPath: String, binPath: String, src: Bitmap, threads: Int = 4): Bitmap? {
        if (!ensureLoaded()) return null
        return try {
            nativeProcess(paramPath, binPath, src, threads)
        } catch (t: Throwable) {
            android.util.Log.e("SRTEST", "native process failed", t)
            null
        }
    }

    private external fun nativeProcess(
        paramPath: String,
        binPath: String,
        bitmap: Bitmap,
        threads: Int,
    ): Bitmap?
}
