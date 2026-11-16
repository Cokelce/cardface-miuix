package com.dsh.miuix.cardface.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.MediaStore
import com.dsh.miuix.cardface.imaging.Resampler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 卡面海报：横版 2880×2160 / 竖版 2160×2880，同样的柔光渐变背景，多种版式。 */
object CardPoster {

    enum class Style(val label: String) { CLASSIC("经典"), SPLIT("分栏"), MINIMAL("留白") }
    data class Palette(val label: String, val bgFrom: String, val bgTo: String, val text: String, val sub: String, val accent: String)
    val palettes = listOf(
        Palette("雾蓝", "#F7FAFF", "#E7F0FF", "#0B1B33", "#64758C", "#3482FF"),
        Palette("暖沙", "#FFFBF5", "#F2E5D5", "#2B2118", "#7C6B5A", "#C98A4B"),
        Palette("樱粉", "#FFF6FA", "#FBE4EF", "#2A1220", "#876070", "#E84A8A"),
        Palette("墨黑", "#0A0D12", "#151B26", "#F5F7FA", "#9AA5B5", "#6B93FF"),
    )

    fun create(
        context: Context,
        image: File,
        name: String,
        style: Style = Style.CLASSIC,
        paletteIndex: Int = 0,
        portrait: Boolean = true,
    ): Bitmap {
        val card = decode(image) ?: error("图片读取失败")
        val palette = palettes[paletteIndex.coerceIn(palettes.indices)]
        val W = if (portrait) 2160 else 2880
        val H = if (portrait) 2880 else 2160
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val accent = Color.parseColor(palette.accent)
        val textColor = Color.parseColor(palette.text)
        val subColor = Color.parseColor(palette.sub)

        // 统一的柔光渐变背景（第一版风格）
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, W.toFloat(), H.toFloat(),
                intArrayOf(Color.parseColor(palette.bgFrom), Color.parseColor(palette.bgTo)),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blend(accent, Color.WHITE, 0.72f)
            maskFilter = android.graphics.BlurMaskFilter(150f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(W * 0.12f, H * 0.12f, W * 0.14f, glow)
        canvas.drawCircle(W * 0.9f, H * 0.88f, W * 0.2f, glow)

        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val normal = Typeface.create("sans-serif", Typeface.NORMAL)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // 头部品牌
        p.typeface = bold; p.textSize = 74f; p.color = textColor
        canvas.drawText("卡面工坊", 120f, 170f, p)
        p.typeface = normal; p.textSize = 34f; p.color = subColor
        canvas.drawText("CARD FACE STUDIO", 122f, 222f, p)
        p.textAlign = Paint.Align.RIGHT; p.textSize = 34f
        canvas.drawText(SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(Date()), W - 120f, 170f, p)
        p.textAlign = Paint.Align.LEFT

        when {
            portrait && style == Style.CLASSIC -> {
                val rect = RectF(120f, 470f, 2040f, 1682f)
                drawCard(canvas, card, rect, accent)
                p.typeface = bold; p.textSize = 86f; p.color = textColor
                canvas.drawText(clip(name), 120f, 1900f, p)
                p.typeface = normal; p.textSize = 44f; p.color = subColor
                canvas.drawText("用喜欢的画面，点亮每天的第一眼。", 122f, 1970f, p)
                accentBar(canvas, 120f, 2030f, 380f, accent)
            }
            portrait && style == Style.SPLIT -> {
                p.typeface = bold; p.textSize = 100f; p.color = textColor
                canvas.drawText(clip(name), 120f, 470f, p)
                p.typeface = normal; p.textSize = 42f; p.color = subColor
                canvas.drawText("把喜欢的画面，装进每天都会刷的卡里。", 122f, 540f, p)
                accentBar(canvas, 120f, 590f, 340f, accent)
                val rect = RectF(120f, 680f, 2040f, 1892f)
                drawCard(canvas, card, rect, accent)
                p.textSize = 36f; p.color = subColor
                canvas.drawText("LSPOSED · 小米智能卡卡面", 120f, 1970f, p)
            }
            portrait && style == Style.MINIMAL -> {
                p.typeface = bold; p.textSize = 72f; p.color = textColor
                p.textAlign = Paint.Align.CENTER
                canvas.drawText(clip(name), W / 2f, 640f, p)
                p.textAlign = Paint.Align.LEFT
                val rect = RectF(280f, 760f, 1880f, 1771f)
                drawCard(canvas, card, rect, accent)
                p.typeface = normal; p.textSize = 36f; p.color = subColor
                p.textAlign = Paint.Align.CENTER
                canvas.drawText("用喜欢的画面，点亮每天的第一眼。", W / 2f, 1880f, p)
                p.textAlign = Paint.Align.LEFT
            }
            !portrait && style == Style.CLASSIC -> {
                val rect = RectF(140f, 400f, 1900f, 1513f)
                drawCard(canvas, card, rect, accent)
                p.typeface = bold; p.textSize = 92f; p.color = textColor
                drawWrapped(canvas, clip(name), 2040f, 760f, 700f, p, 2)
                p.typeface = normal; p.textSize = 44f; p.color = subColor
                canvas.drawText("用喜欢的画面，", 2044f, 1080f, p)
                canvas.drawText("点亮每天的第一眼。", 2044f, 1140f, p)
                accentBar(canvas, 2044f, 1200f, 340f, accent)
            }
            !portrait && style == Style.SPLIT -> {
                p.typeface = bold; p.textSize = 120f; p.color = textColor
                canvas.drawText("卡面", 140f, 340f, p)
                p.typeface = normal; p.textSize = 42f; p.color = subColor
                canvas.drawText("STUDIO / POSTER", 144f, 400f, p)
                val rect = RectF(140f, 470f, 1940f, 1583f)
                drawCard(canvas, card, rect, accent)
                p.typeface = bold; p.textSize = 80f; p.color = textColor
                canvas.drawText(clip(name), 2060f, 700f, p)
                p.typeface = normal; p.textSize = 44f; p.color = subColor
                canvas.drawText("用喜欢的画面，", 2064f, 1000f, p)
                canvas.drawText("点亮每天的第一眼。", 2064f, 1060f, p)
                accentBar(canvas, 2064f, 1120f, 300f, accent)
            }
            else -> {
                val rect = RectF(440f, 400f, 2440f, 1663f)
                drawCard(canvas, card, rect, accent)
                p.typeface = bold; p.textSize = 76f; p.color = textColor
                p.textAlign = Paint.Align.CENTER
                canvas.drawText(clip(name), W / 2f, 1830f, p)
                p.typeface = normal; p.textSize = 38f; p.color = subColor
                canvas.drawText("用喜欢的画面，点亮每天的第一眼。", W / 2f, 1900f, p)
                p.textAlign = Paint.Align.LEFT
            }
        }

        // 底部品牌
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (paletteIndex == 3) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000")
            strokeWidth = 2f
        }
        canvas.drawLine(120f, H - 190f, W - 120f, H - 190f, line)
        p.typeface = normal; p.textSize = 32f; p.color = subColor
        p.textAlign = Paint.Align.LEFT
        canvas.drawText("LSPOSED · 小米智能卡卡面", 120f, H - 130f, p)
        p.textAlign = Paint.Align.RIGHT
        canvas.drawText("DIY NFC CARD FACE", W - 120f, H - 130f, p)
        p.textAlign = Paint.Align.LEFT
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 150 }
        for (i in 0..4) canvas.drawCircle(120f + i * 46f, H - 225f, 9f, dot)
        card.recycle()
        return out
    }

    private fun drawCard(canvas: Canvas, card: Bitmap, rect: RectF, accent: Int) {
        val targetW = rect.width().toInt()
        val targetH = rect.height().toInt()
        val scaled = runCatching {
            Resampler.lanczos3(card.toPixelImage(), targetW, targetH, linearLight = false).toBitmap()
        }.getOrElse { Bitmap.createScaledBitmap(card, targetW, targetH, true) }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44000000")
            maskFilter = android.graphics.BlurMaskFilter(48f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(RectF(rect.left, rect.top + 24f, rect.right, rect.bottom + 32f), 42f, 42f, shadow)
        val clip = Path().apply { addRoundRect(rect, 34f, 34f, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(clip)
        canvas.drawBitmap(scaled, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = accent; alpha = 80
        }
        canvas.drawRoundRect(rect, 34f, 34f, border)
        if (scaled !== card) scaled.recycle()
    }

    private fun accentBar(canvas: Canvas, x: Float, y: Float, w: Float, accent: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        canvas.drawRoundRect(RectF(x, y, x + w, y + 14f), 7f, 7f, p)
    }

    private fun clip(text: String): String = if (text.length <= 10) text else text.take(10) + "…"

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint, maxLines: Int) {
        var line = StringBuilder(); var ty = y; var lines = 0
        for (ch in text) {
            if (paint.measureText(line.toString() + ch) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line.toString(), x, ty, paint)
                line = StringBuilder(); ty += paint.textSize * 1.15f; lines++
                if (lines >= maxLines - 1) break
            }
            line.append(ch)
        }
        if (line.isNotEmpty() && lines < maxLines) canvas.drawText(line.toString(), x, ty, paint)
    }

    fun save(context: Context, template: Bitmap): File {
        val f = File(context.cacheDir, "cardface-poster.png")
        f.outputStream().use { template.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/卡面工坊")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 800)
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun blend(color: Int, with: Int, ratio: Float): Int {
        val r = (Color.red(color) * (1 - ratio) + Color.red(with) * ratio).toInt()
        val g = (Color.green(color) * (1 - ratio) + Color.green(with) * ratio).toInt()
        val b = (Color.blue(color) * (1 - ratio) + Color.blue(with) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
}
