package com.dsh.miuix.cardface.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream

/**
 * 低分辨率卡面超分：调用内置 Real-CUGAN-SE(ncnn) CLI。
 * 模型只有 2.5MB，和 ncnn 库一起内置，不额外下载。
 *
 * 约定：输入先补 36px 边缘，CLI 输出会裁掉 36px 边框再 2x，所以输出裁 72px 后就是精确 2 倍。
 */
object SuperResolution {

    private const val PAD = 36
    private const val MODEL_DIR = "models-se"
    private const val MODEL_NAME = "up2x-conservative"
    private const val MAX_SIDE = 1600

    @Volatile private var modelExtracted = false

    fun isSupported(): Boolean = try {
        File(System.getProperty("os.arch") ?: "").name.isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    /** 源裁剪尺寸低于目标卡面尺寸时，先 2x 超分；失败返回 null（调用方直接降采样兜底）。 */
    fun upscaleIfNeeded(context: Context, src: Bitmap, targetW: Int, targetH: Int): Bitmap? {
        if (src.width >= targetW && src.height >= targetH) return null
        if (src.width <= 0 || src.height <= 0) return null
        if (src.width > MAX_SIDE || src.height > MAX_SIDE) return null
        return runCatching { upscale2x(context, src) }.getOrNull()
    }

    private fun upscale2x(context: Context, src: Bitmap): Bitmap? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val exe = File(nativeDir, "librealcugan-ncnn.so")
        if (!exe.exists()) return null
        val modelDir = ensureModel(context) ?: return null
        val work = File(context.cacheDir, "sr").apply { mkdirs() }

        val padded = pad(src, PAD)
        val input = File(work, "in.png")
        val output = File(work, "out.png")
        output.delete()
        FileOutputStream(input).use { padded.compress(Bitmap.CompressFormat.PNG, 100, it) }
        padded.recycle()

        val cmd = listOf(
            "/system/bin/linker64", exe.absolutePath,
            "-i", input.absolutePath,
            "-o", output.absolutePath,
            "-s", "2",
            "-n", "0",
            "-m", modelDir.absolutePath,
            "-g", "-1",
            "-j", "1:2:2",
        )
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath }
            .start()
        val finished = proc.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0 || !output.exists()) return null

        val decoded = BitmapFactory.decodeFile(output.absolutePath) ?: return null
        val cropX = PAD * 2
        val cropY = PAD * 2
        val w = decoded.width - cropX * 2
        val h = decoded.height - cropY * 2
        if (w <= 0 || h <= 0) return decoded
        val cropped = Bitmap.createBitmap(decoded, cropX, cropY, w, h)
        if (cropped !== decoded) decoded.recycle()
        input.delete()
        output.delete()
        return cropped
    }

    /** 模型释放到 filesDir（只做一次）。 */
    private fun ensureModel(context: Context): File? {
        val dir = File(context.filesDir, MODEL_DIR)
        val param = File(dir, "$MODEL_NAME.param")
        val bin = File(dir, "$MODEL_NAME.bin")
        if (modelExtracted && param.exists() && bin.exists()) return dir
        return runCatching {
            dir.mkdirs()
            listOf("$MODEL_NAME.param", "$MODEL_NAME.bin").forEach { name ->
                val dst = File(dir, name)
                if (!dst.exists() || dst.length() == 0L) {
                    context.assets.open("$MODEL_DIR/$name").use { input ->
                        FileOutputStream(dst).use { out -> input.copyTo(out) }
                    }
                }
            }
            modelExtracted = true
            dir
        }.getOrNull()
    }

    /** 边缘复制补 36px，保证 CLI 裁边后内容完整。 */
    private fun pad(src: Bitmap, pad: Int): Bitmap {
        val w = src.width + pad * 2
        val h = src.height + pad * 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(0xFF000000.toInt())
        canvas.drawBitmap(src, pad.toFloat(), pad.toFloat(), paint)
        val rect = { l: Int, t: Int, r: Int, b: Int, dl: Int, dt: Int, dr: Int, db: Int ->
            canvas.drawBitmap(src, Rect(l, t, r, b), Rect(dl, dt, dr, db), paint)
        }
        // 上下左右边缘复制
        rect(0, 0, src.width, 1, pad, 0, pad + src.width, pad)
        rect(0, src.height - 1, src.width, src.height, pad, pad + src.height, pad + src.width, h)
        rect(0, 0, 1, src.height, 0, pad, pad, pad + src.height)
        rect(src.width - 1, 0, src.width, src.height, pad + src.width, pad, w, pad + src.height)
        // 四角
        rect(0, 0, 1, 1, 0, 0, pad, pad)
        rect(src.width - 1, 0, src.width, 1, pad + src.width, 0, w, pad)
        rect(0, src.height - 1, 1, src.height, 0, pad + src.height, pad, h)
        rect(src.width - 1, src.height - 1, src.width, src.height, pad + src.width, pad + src.height, w, h)
        return out
    }
}
