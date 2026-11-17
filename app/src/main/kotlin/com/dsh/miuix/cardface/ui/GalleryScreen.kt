package com.dsh.miuix.cardface.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dsh.miuix.cardface.core.GalleryImage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 图库：3 列网格 + 全屏预览 + 长按删除 + 添加图片（相册 → 裁剪 → 存图库）。
 */
@Composable
fun GalleryScreen(
    model: AppModel,
    innerPadding: PaddingValues,
    onPickForCrop: (List<android.net.Uri>) -> Unit,
    onOpenCatalog: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<GalleryImage?>(null) }
    var lastPreview by remember { mutableStateOf<GalleryImage?>(null) }
    var deleteTarget by remember { mutableStateOf<GalleryImage?>(null) }
    var actionTarget by remember { mutableStateOf<GalleryImage?>(null) }
    var renameTarget by remember { mutableStateOf<GalleryImage?>(null) }
    var poster by remember { mutableStateOf<Bitmap?>(null) }
    var posterSource by remember { mutableStateOf<GalleryImage?>(null) }
    var posterStyle by remember { mutableStateOf(com.dsh.miuix.cardface.core.CardPoster.Style.CLASSIC) }
    var posterPalette by remember { mutableIntStateOf(0) }
    var posterPortrait by remember { mutableStateOf(true) }
    var posterLoading by remember { mutableStateOf(false) }
    var posterOpen by remember { mutableStateOf(false) }
    val accent = MiuixTheme.colorScheme.primary.toArgb()

    fun buildPoster(image: GalleryImage, style: com.dsh.miuix.cardface.core.CardPoster.Style, palette: Int) {
        posterLoading = true
        scope.launch {
            kotlinx.coroutines.yield()
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    com.dsh.miuix.cardface.core.CardPoster.create(
                        context, image.file, image.name.substringBeforeLast('.'), style, palette, posterPortrait,
                    )
                }.getOrNull()
            }
            poster = bmp
            posterLoading = false
            if (bmp == null) model.notify("海报生成失败")
        }
    }
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(preview) {
        if (preview != null) lastPreview = preview
        model.galleryPreviewVisible = preview != null
    }
    DisposableEffect(Unit) {
        onDispose { model.galleryPreviewVisible = false }
    }
    LaunchedEffect(posterOpen) { model.posterPreviewVisible = posterOpen }
    BackHandler(enabled = posterOpen) { posterOpen = false }
    BackHandler(enabled = preview != null) { preview = null }

    val revision = model.prefs.revision + model.storeRevision
    val images = remember(revision, tick) {
        runCatching {
            val applied = model.prefs.mappedImageNames().toSet()
            model.store.list().filterNot {
                it.name in applied ||
                    it.name == "cardface-test.png" ||
                    it.name.startsWith("demo-card-")
            }
        }.getOrDefault(emptyList())
    }

    // 多选：一次最多 9 张，裁剪时按顺序一张一张处理。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        if (uris.isNotEmpty()) onPickForCrop(uris)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                LargeTitleHeader(
                    title = "图库",
                    contentPadding = PaddingValues(start = 4.dp, end = 2.dp, top = 14.dp, bottom = 10.dp),
                ) {
                    if (model.prefs.catalogEnabled) {
                        IconButton(onClick = onOpenCatalog) {
                            Icon(
                                imageVector = MiuixIcons.Store,
                                contentDescription = "卡面库",
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = "添加图片",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                }
            }

            if (images.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyCard(
                        icon = MiuixIcons.Photos,
                        title = "图库还是空的",
                        description = "从相册选一张图，裁剪后就会保存到这里；\n编辑卡面时也能直接选用。",
                    ) {
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(
                            text = "添加图片",
                            onClick = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                items(items = images, key = { it.name }) { image ->
                    GalleryTile(
                        modifier = if (animationsEnabled) Modifier.animateItem() else Modifier,
                        image = image,
                        onClick = { preview = image },
                        onLongPress = { actionTarget = image },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = preview != null,
            enter = if (animationsEnabled) {
                fadeIn(animationSpec = tween(180)) +
                    scaleIn(initialScale = 0.96f, animationSpec = tween(180))
            } else {
                EnterTransition.None
            },
            exit = if (animationsEnabled) {
                fadeOut(animationSpec = tween(140)) +
                    scaleOut(targetScale = 0.98f, animationSpec = tween(140))
            } else {
                ExitTransition.None
            },
            label = "galleryPreview",
        ) {
            lastPreview?.let { image ->
                GalleryPreview(
                    image = image,
                    onClose = { preview = null },
                    onShare = {
                        posterSource = image
                        preview = null
                        poster = null
                        posterOpen = true
                        buildPoster(image, posterStyle, posterPalette)
                    },
                )
            }
        }

        if (posterOpen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { posterOpen = false }) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        text = "分享海报",
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    poster?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "分享海报",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(if (posterPortrait) 3f / 4f else 4f / 3f)
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    if (posterLoading) {
                        Text(
                            text = "生成中…",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(vertical = 10.dp),
                ) {
                    Text(
                        text = "版式",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            text = (if (posterPortrait) "● " else "") + "竖版",
                            onClick = {
                                posterPortrait = true
                                posterSource?.let { buildPoster(it, posterStyle, posterPalette) }
                            },
                        )
                        TextButton(
                            text = (if (!posterPortrait) "● " else "") + "横版",
                            onClick = {
                                posterPortrait = false
                                posterSource?.let { buildPoster(it, posterStyle, posterPalette) }
                            },
                        )
                    }
                    Text(
                        text = "样式",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(com.dsh.miuix.cardface.core.CardPoster.Style.entries.size) { i ->
                            val st = com.dsh.miuix.cardface.core.CardPoster.Style.entries[i]
                            TextButton(
                                text = (if (posterStyle == st) "● " else "") + st.label,
                                onClick = {
                                    posterStyle = st
                                    posterSource?.let { buildPoster(it, st, posterPalette) }
                                },
                            )
                        }
                    }
                    Text(
                        text = "配色",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(com.dsh.miuix.cardface.core.CardPoster.palettes.size) { i ->
                            TextButton(
                                text = (if (posterPalette == i) "● " else "") + com.dsh.miuix.cardface.core.CardPoster.palettes[i].label,
                                onClick = {
                                    posterPalette = i
                                    posterSource?.let { buildPoster(it, posterStyle, i) }
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = "保存到相册",
                        onClick = {
                            poster?.let { bmp ->
                                val ok = com.dsh.miuix.cardface.core.CardPoster.saveToGallery(context, bmp, "卡面工坊海报")
                                model.notify(if (ok) "海报已保存到相册" else "保存失败")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "分享",
                        onClick = {
                            poster?.let { bmp ->
                                val file = com.dsh.miuix.cardface.core.CardPoster.save(context, bmp)
                                com.dsh.miuix.cardface.core.CardShare.shareImage(context, file)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    val acting = actionTarget
    if (acting != null) {
        OverlayDialog(
            show = true,
            title = "图片操作",
            summary = acting.name,
            onDismissRequest = { actionTarget = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "重命名",
                    onClick = {
                        renameTarget = acting
                        actionTarget = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = "删除",
                    onClick = {
                        deleteTarget = acting
                        actionTarget = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.error,
                        textColor = MiuixTheme.colorScheme.onError,
                    ),
                )
                TextButton(
                    text = "取消",
                    onClick = { actionTarget = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    val renaming = renameTarget
    if (renaming != null) {
        var input by remember(renaming.name) {
            mutableStateOf(renaming.name.removeSuffix(".png"))
        }
        OverlayDialog(
            show = true,
            title = "重命名图片",
            summary = "改的是图库里的文件名；已经应用的卡面会同步更新。",
            onDismissRequest = { renameTarget = null },
        ) {
            TextField(
                value = input,
                onValueChange = { input = it.take(60) },
                label = "图片名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "取消",
                    onClick = { renameTarget = null },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "保存",
                    onClick = {
                        val oldName = renaming.name
                        val newBase = input
                        renameTarget = null
                        scope.launch {
                            runCatching {
                                val newName = model.store.rename(oldName, newBase)
                                model.prefs.allCardUrls().forEach { url ->
                                    if (model.prefs.replacementFor(url) == oldName) {
                                        model.prefs.setReplacement(url, newName)
                                    }
                                }
                                com.dsh.miuix.cardface.core.CardSync.writeMapSnapshot(
                                    context,
                                    model.prefs,
                                )
                                model.bumpStore()
                                tick++
                                newName
                            }.onSuccess {
                                model.notify("已重命名为 $it")
                            }.onFailure {
                                model.notify("重命名失败：${it.message}")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    val target = deleteTarget
    ConfirmDialog(
        show = target != null,
        title = "删除这张图？",
        message = "「${target?.name ?: ""}」会从图库删除；已经用到卡面上的映射也会一起清掉。",
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            val image = target
            deleteTarget = null
            if (image != null) {
                scope.launch {
                    // 清掉引用这张图的卡面映射
                    runCatching {
                        model.prefs.allCardUrls().forEach { url ->
                            if (model.prefs.replacementFor(url) == image.name) {
                                model.prefs.setReplacement(url, null)
                            }
                        }
                        model.store.delete(image.name)
                        com.dsh.miuix.cardface.core.CardSync.writeMapSnapshot(context, model.prefs)
                        tick++
                    }.onFailure { model.notify("删除失败：${it.message}") }
                    model.notify("已删除 ${image.name}")
                }
            }
        },
        onDismiss = { deleteTarget = null },
    )
}

@Composable
private fun GalleryTile(
    image: GalleryImage,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        onClick = onClick,
        onLongPress = onLongPress,
    ) {
        Column {
            AppAsyncImage(
                model = "file://${image.file.absolutePath}",
                contentDescription = image.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CardImaging.TARGET_RATIO)
                    .clip(RoundedCornerShape(16.dp)),
                backgroundBrush = cardThumbnailGradient(),
            )
            Text(
                text = image.displayName,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 全屏预览（深色背景 + 关闭）。 */
@Composable
private fun GalleryPreview(image: GalleryImage, onClose: () -> Unit, onShare: () -> Unit) {
    // 深色用纯黑（看图更沉浸），浅色跟随主题背景。
    val previewBackground = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) {
        Color.Black
    } else {
        MiuixTheme.colorScheme.background
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(previewBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        AsyncImage(
            model = "file://${image.file.absolutePath}",
            contentDescription = image.name,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentScale = ContentScale.Fit,
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            TextButton(text = "分享", onClick = onShare)
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "关闭",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        }
        Text(
            text = image.name,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )
    }
}
