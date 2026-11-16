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

/** 把卡面生成 4:3 高清海报（2880×2160），多种样式/配色。 */
object CardPoster {

    const val W = 2880
    const val H = 2160

    enum class Style(val label: String) { MAGAZINE("杂志"), POSTCARD("明信片"), FILM("胶片") }
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
        style: Style = Style.MAGAZINE,
        paletteIndex: Int = 0,
    ): Bitmap {
        val card = decode(image) ?: error("图片读取失败")
        val palette = palettes[paletteIndex.coerceIn(palettes.indices)]
        val dark = paletteIndex == 3
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, W.toFloat(), H.toFloat(),
                intArrayOf(Color.parseColor(palette.bgFrom), Color.parseColor(palette.bgTo)),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        val accent = Color.parseColor(palette.accent)
        val textColor = Color.parseColor(palette.text)
        val subColor = Color.parseColor(palette.sub)
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val normal = Typeface.create("sans-serif", Typeface.NORMAL)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // 品牌头部
        p.typeface = bold; p.textSize = 82f; p.color = textColor
        canvas.drawText("卡面工坊", 120f, 170f, p)
        p.typeface = normal; p.textSize = 40f; p.color = subColor
        canvas.drawText("CARD FACE STUDIO", 122f, 232f, p)
        p.textAlign = Paint.Align.RIGHT; p.textSize = 42f
        canvas.drawText(SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(Date()), 2760f, 170f, p)
        p.textAlign = Paint.Align.LEFT

        val cardRect: RectF
        when (style) {
            Style.MAGAZINE -> {
                cardRect = RectF(120f, 470f, 1920f, 1606f)
                drawCard(canvas, card, cardRect, accent)
                p.typeface = bold; p.textSize = 96f; p.color = textColor
                drawWrapped(canvas, canvasText(name.ifBlank { "我的卡面" }), 2080f, 760f, 640f, p, 3)
                p.typeface = normal; p.textSize = 46f; p.color = subColor
                canvas.drawText("用喜欢的画面，", 2084f, 1100f, p)
                canvas.drawText("点亮每天的第一眼。", 2084f, 1166f, p)
                val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
                canvas.drawRoundRect(RectF(2084f, 1240f, 2436f, 1258f), 9f, 9f, bar)
            }
            Style.POSTCARD -> {
                cardRect = RectF(440f, 540f, 2440f, 1802f)
                drawCard(canvas, card, cardRect, accent)
                p.typeface = bold; p.textSize = 84f; p.color = textColor
                p.textAlign = Paint.Align.CENTER
                canvas.drawText(name.ifBlank { "我的卡面" }, W / 2f, 1930f, p)
                p.typeface = normal; p.textSize = 46f; p.color = subColor
                canvas.drawText("用喜欢的画面，点亮每天的第一眼。", W / 2f, 2000f, p)
                p.textAlign = Paint.Align.LEFT
            }
            Style.FILM -> {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent; alpha = 40
                    maskFilter = android.graphics.BlurMaskFilter(110f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawCircle(1440f, 1050f, 700f, glow)
                cardRect = RectF(490f, 500f, 2390f, 1700f)
                drawCard(canvas, card, cardRect, accent)
                p.textAlign = Paint.Align.CENTER
                p.typeface = bold; p.textSize = 88f; p.color = textColor
                canvas.drawText(name.ifBlank { "我的卡面" }, W / 2f, 1850f, p)
                p.typeface = normal; p.textSize = 46f; p.color = subColor
                canvas.drawText("KEEP THE MOMENT · CARD FACE STUDIO", W / 2f, 1930f, p)
                p.textAlign = Paint.Align.LEFT
            }
        }

        // 底部品牌与装饰
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000")
            strokeWidth = 2f
        }
        canvas.drawLine(120f, 2070f, 2760f, 2070f, line)
        p.typeface = normal; p.textSize = 36f; p.color = subColor
        canvas.drawText("LSPosed · 小米智能卡卡面", 120f, 2130f, p)
        p.textAlign = Paint.Align.RIGHT
        canvas.drawText("DIY NFC CARD FACE", 2760f, 2130f, p)
        p.textAlign = Paint.Align.LEFT
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 160 }
        for (i in 0..4) canvas.drawCircle(120f + i * 52f, 2030f, 11f, dot)
        card.recycle()
        return out
    }

    private fun drawCard(canvas: Canvas, card: Bitmap, rect: RectF, accent: Int) {
        val targetW = rect.width().toInt()
        val targetH = rect.height().toInt()
        // 用 Lanczos3 放大，比系统 bilinear 清晰
        val scaled = runCatching {
            Resampler.lanczos3(card.toPixelImage(), targetW, targetH, linearLight = false).toBitmap()
        }.getOrElse {
            Bitmap.createScaledBitmap(card, targetW, targetH, true)
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44000000")
            maskFilter = android.graphics.BlurMaskFilter(48f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(RectF(rect.left, rect.top + 26f, rect.right, rect.bottom + 34f), 48f, 48f, shadow)
        val clip = Path().apply { addRoundRect(rect, 40f, 40f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(scaled, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = accent; alpha = 90
        }
        canvas.drawRoundRect(rect, 40f, 40f, border)
        if (scaled !== card) scaled.recycle()
    }

    private fun canvasText(text: String): String = if (text.length <= 8) text else text.take(8) + "…"

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint, maxLines: Int) {
        var line = StringBuilder()
        var ty = y
        var lines = 0
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
}
