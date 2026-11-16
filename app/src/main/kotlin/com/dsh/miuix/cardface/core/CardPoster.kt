package com.dsh.miuix.cardface.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把卡面生成一张 3:4 的海报，用于分享/收藏。 */
object CardPoster {

    const val W = 1080
    const val H = 1440

    fun create(context: Context, image: File, name: String, accent: Int): Bitmap {
        val card = decode(image) ?: error("图片读取失败")
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val accentColor = if (Color.alpha(accent) == 0) Color.parseColor("#3482FF") else accent

        // 背景：主色柔光渐变
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, W.toFloat(), H.toFloat(),
                intArrayOf(
                    blend(accentColor, Color.WHITE, 0.82f),
                    Color.WHITE,
                    blend(accentColor, Color.WHITE, 0.92f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blend(accentColor, Color.WHITE, 0.55f)
            maskFilter = android.graphics.BlurMaskFilter(120f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(120f, 180f, 180f, glow)
        canvas.drawCircle(980f, 1260f, 240f, glow)

        // 顶部品牌
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val normal = Typeface.create("sans-serif", Typeface.NORMAL)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = bold
        p.textSize = 44f
        p.color = Color.parseColor("#0B1B33")
        canvas.drawText("卡面工坊", 72f, 132f, p)
        p.typeface = normal
        p.textSize = 24f
        p.color = Color.parseColor("#5A6B82")
        canvas.drawText("CARD FACE STUDIO", 74f, 172f, p)
        p.textSize = 24f
        p.textAlign = Paint.Align.RIGHT
        p.color = Color.parseColor("#7A8AA0")
        canvas.drawText(SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(Date()), 1008f, 132f, p)
        p.textAlign = Paint.Align.LEFT

        // 卡面卡片 + 阴影
        val left = 60f
        val top = 252f
        val rect = RectF(left, top, left + 960f, top + 606f)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#330B1B33")
            maskFilter = android.graphics.BlurMaskFilter(38f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(RectF(rect.left, rect.top + 18f, rect.right, rect.bottom + 24f), 42f, 42f, shadow)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val src = android.graphics.Rect(0, 0, card.width, card.height)
        canvas.save()
        val clip = android.graphics.Path().apply { addRoundRect(rect, 36f, 36f, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawBitmap(card, src, rect, cardPaint)
        canvas.restore()

        // 卡面名称 + 标语
        p.typeface = bold
        p.textSize = 46f
        p.color = Color.parseColor("#0B1B33")
        canvas.drawText(name.ifBlank { "我的卡面" }, 72f, 1000f, p)
        p.typeface = normal
        p.textSize = 28f
        p.color = Color.parseColor("#65758B")
        canvas.drawText("用喜欢的画面，点亮每天的第一眼。", 74f, 1052f, p)

        // 分隔线 + 底部信息
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A3482FF")
            strokeWidth = 2f
        }
        canvas.drawLine(72f, 1120f, 1008f, 1120f, line)
        p.textSize = 24f
        p.color = Color.parseColor("#7A8AA0")
        canvas.drawText("LSPosed · 小米智能卡卡面", 72f, 1170f, p)
        p.textAlign = Paint.Align.RIGHT
        canvas.drawText("DIY NFC CARD FACE", 1008f, 1170f, p)
        p.textAlign = Paint.Align.LEFT

        // 底部小圆点装饰
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blend(accentColor, Color.WHITE, 0.4f) }
        for (i in 0..4) canvas.drawCircle(72f + i * 34f, 1240f, 8f, dot)
        return out
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
        val sample = maxOf(1, bounds.outWidth / 1200)
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun blend(color: Int, with: Int, ratio: Float): Int {
        val r = (Color.red(color) * (1 - ratio) + Color.red(with) * ratio).toInt()
        val g = (Color.green(color) * (1 - ratio) + Color.green(with) * ratio).toInt()
        val b = (Color.blue(color) * (1 - ratio) + Color.blue(with) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
}
