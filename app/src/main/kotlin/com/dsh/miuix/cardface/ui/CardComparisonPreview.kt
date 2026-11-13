package com.dsh.miuix.cardface.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A fixed three-part comparison, so the original never gets replaced in the left pane. */
@Composable
fun CardComparisonPreview(
    originalUrl: String,
    replacementModel: String?,
    previewBitmap: Bitmap? = null,
    pending: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ComparisonSide("原图", originalUrl, null, Modifier.weight(1f))
        Text("→", style = MiuixTheme.textStyles.headline2,
            color = MiuixTheme.colorScheme.primary, textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp))
        ComparisonSide(if (pending) "替换预览 · 待保存" else "替换后",
            replacementModel, previewBitmap, Modifier.weight(1f))
    }
}

@Composable
private fun ComparisonSide(label: String, model: String?, bitmap: Bitmap?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(CardImaging.TARGET_RATIO)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> Image(bitmap.asImageBitmap(), label,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                !model.isNullOrBlank() -> AppAsyncImage(model = model,
                    contentDescription = label, modifier = Modifier.fillMaxSize(),
                    backgroundBrush = cardThumbnailGradient())
                else -> Text("未设置替换图", style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
    }
}
