package com.dsh.miuix.cardface.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateListOf
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import top.yukonga.miuix.kmp.icon.extended.Filter
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.miuix.cardface.core.catalog.CatalogCategory
import com.dsh.miuix.cardface.core.catalog.CatalogItem
import com.dsh.miuix.cardface.core.catalog.CatalogPage
import com.dsh.miuix.cardface.core.catalog.CatalogRepository
import com.dsh.miuix.cardface.core.catalog.HibankCatalogSource
import com.dsh.miuix.cardface.core.catalog.PinyinSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 卡面库：浏览 / 收藏 / 最近使用；银行卡面、交通卡面、IP 联动三类。
 *
 * 图片来自第三方 Hibank（hi.my-api.cn），按需下载到本地、校验后才走现有裁剪/替换流程，
 * 不会把远程 URL 直接交给卡包。
 */
@Composable
fun CatalogScreen(
    model: AppModel,
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onUse: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository(context) }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableIntStateOf(0) } // 0 浏览 1 收藏 2 最近
    var categoryIndex by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var appliedSearch by remember { mutableStateOf("") }
    var appliedFilter by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var refresh by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var itemsCategory by remember { mutableStateOf<CatalogCategory?>(null) }
    var pageInfo by remember { mutableStateOf<CatalogPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CatalogItem?>(null) }
    var downloadState by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var localRevision by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var loadMoreJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var showIndex by remember { mutableStateOf(false) }
    var showIpJump by remember { mutableStateOf(false) }
    var ipQuery by remember { mutableStateOf("") }
    val collapsedGroups = remember { mutableStateListOf<String>() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun applySearch() {
        appliedSearch = search.trim()
        appliedFilter = filter.trim()
        page = 1
        refresh++
        keyboard?.hide()
    }

    // 搜索框自动生效：停止输入约 450ms 就搜索，避免只有点图标/按键盘才生效。
    LaunchedEffect(search, filter, mode) {
        if (mode != 0) return@LaunchedEffect
        val nextSearch = search.trim()
        val nextFilter = filter.trim()
        if (nextSearch == appliedSearch && nextFilter == appliedFilter) return@LaunchedEffect
        delay(450)
        appliedSearch = nextSearch
        appliedFilter = nextFilter
        page = 1
        refresh++
    }

    val category = CatalogCategory.entries[categoryIndex]
    val gridState = rememberLazyGridState()
    val browseItems = if (mode == 0) {
        // 只展示当前分类的数据，防止切换分类瞬间旧分类条目串进来。
        if (itemsCategory == category) items else emptyList()
    } else remember(mode, localRevision) {
        if (mode == 1) repo.favorites() else repo.recents()
    }

    // 通讯录式分组：进入分类时数据已全部加载，这里只做分组和字母定位。
    val groups = browseItems.groupBy {
        it.groupName.ifBlank { it.subtitle }.ifBlank { "其他" }
    }
    val indexData: Pair<Map<Char, Int>, Map<String, Int>> =
        remember(groups, collapsedGroups.toList(), mode, error, loading) {
            if (mode != 0) return@remember emptyMap<Char, Int>() to emptyMap()
            var index = 5 // header / 模式 / 分类 / 搜索 / 状态
            if (error != null) index += 1
            if (!loading && error == null && browseItems.isEmpty()) index += 1
            val letters = LinkedHashMap<Char, Int>()
            val perGroup = LinkedHashMap<String, Int>()
            groups.forEach { (name, list) ->
                val letter = PinyinSort.initial(name)
                if (!letters.containsKey(letter)) letters[letter] = index
                perGroup[name] = index
                index += 1 + if (name in collapsedGroups) 0 else list.size
            }
            letters to perGroup
        }
    val anchors = indexData.first
    val groupAnchors = indexData.second
    val currentLetter = anchors.entries.lastOrNull {
        it.value <= gridState.firstVisibleItemIndex
    }?.key

    fun openSite() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(category.webUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // 浏览模式：分类/搜索变化时重新加载第一页
    LaunchedEffect(categoryIndex, appliedSearch, appliedFilter, refresh, mode) {
        if (mode != 0) {
            loadMoreJob?.cancel()
            loading = false
            return@LaunchedEffect
        }
        generation++
        loadMoreJob?.cancel()
        loadMoreJob = null
        loading = true
        error = null
        items = emptyList()
        itemsCategory = null
        pageInfo = null
        page = 1
        try {
            val result = withContext(Dispatchers.IO) {
                repo.load(category, 1, appliedSearch, appliedFilter)
            }
            items = result.items
            itemsCategory = category
            pageInfo = result
            gridState.scrollToItem(0)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            error = t.message ?: "目录加载失败，请重试"
        } finally {
            loading = false
        }
    }

    // 翻页：只追加，不打断已加载内容
    // 接近底部时自动加载下一页；追加在下方，不调整滚动位置，列表自然向下延伸。
    fun loadMore() {
        if (mode != 0 || loading) return
        val info = pageInfo ?: return
        if (page >= info.pages) return
        loading = true
        error = null
        val next = page + 1
        page = next
        val requestGeneration = generation
        val requestCategory = category
        val requestSearch = appliedSearch
        val requestFilter = appliedFilter
        loadMoreJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repo.load(requestCategory, next, requestSearch, requestFilter)
                }
                if (requestGeneration != generation) return@launch
                if (itemsCategory != requestCategory) return@launch
                items = (items + result.items).distinctBy { it.key }
                pageInfo = result
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                error = t.message ?: "加载下一页失败"
                page = (next - 1).coerceAtLeast(1)
            } finally {
                loading = false
            }
        }
    }

    // 分类 / 搜索变化时必须重建这个 effect，否则闭包会拿着旧分类的 loadMore 往新列表里追加。
    LaunchedEffect(mode, categoryIndex, appliedSearch, appliedFilter, refresh) {
        if (mode != 0) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.distinctUntilChanged().collect { (last, total) ->
            // 必须已经滚动过（不在顶部）才自动翻页，避免刚打开就自己加载第二页。
            if (gridState.firstVisibleItemIndex > 0 && total > 0 && last >= total - 5) loadMore()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    BackHeader(title = "卡面库", onBack = onBack) {
                        IconButton(onClick = { refresh++ }) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "刷新",
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "mode") {
                CatalogPillRow(
                    items = listOf("浏览", "收藏", "最近使用"),
                    selected = mode,
                    onSelected = { mode = it },
                )
            }

            if (mode == 0) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "category") {
                    CatalogTabRow(
                        items = CatalogCategory.entries.map { it.label },
                        selected = categoryIndex,
                        onSelected = { index ->
                            if (index != categoryIndex) {
                                categoryIndex = index
                                search = ""
                                filter = ""
                                appliedSearch = ""
                                appliedFilter = ""
                                showFilter = false
                                page = 1
                                refresh++
                            }
                        },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        insideMargin = PaddingValues(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextField(
                                value = search,
                                onValueChange = { search = it.take(120) },
                                label = "搜索卡面名称",
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { applySearch() }),
                            )
                            IconButton(onClick = { applySearch() }) {
                                Icon(
                                    imageVector = MiuixIcons.Search,
                                    contentDescription = "搜索",
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = {
                                    showFilter = !showFilter
                                    if (!showFilter) {
                                        filter = ""
                                        if (appliedFilter.isNotEmpty()) applySearch()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Filter,
                                    contentDescription = "筛选",
                                    tint = if (showFilter || appliedFilter.isNotBlank()) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                )
                            }
                        }
                        if (showFilter) {
                            TextField(
                                value = filter,
                                onValueChange = { filter = it.take(120) },
                                label = category.filterLabel,
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { applySearch() }),
                            )
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            CatalogStatusLine(
                                loading = loading,
                                total = pageInfo?.total ?: 0,
                                cached = pageInfo?.cached == true,
                            )
                        }
                        if (mode == 0 && groups.size > 8) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextButton(text = "A-Z", onClick = { showIndex = true })
                                TextButton(text = "IP 跳转", onClick = { showIpJump = true })
                            }
                        }
                    }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                    Text(
                        text = if (mode == 1) "已收藏 ${browseItems.size} 张" else "最近使用 ${browseItems.size} 张",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }

            if (error != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = error.orEmpty(),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(text = "重试", onClick = { refresh++ })
                            TextButton(text = "打开来源网站", onClick = { openSite() })
                        }
                    }
                }
            }

            if (!loading && error == null && browseItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyCard(
                        icon = MiuixIcons.Store,
                        title = when (mode) {
                            1 -> "还没有收藏"
                            2 -> "还没有使用记录"
                            else -> "没有找到匹配的卡面"
                        },
                        description = when (mode) {
                            1 -> "在卡面详情里点收藏，之后能在这里快速找到。"
                            2 -> "使用过的在线卡面会按时间显示在这里。"
                            else -> "换个更短的关键词，或切换分类再试。"
                        },
                    )
                }
            }

            // 固定按 IP / 银行 / 城市分组；每组可点标题折叠。
            groups.forEach { (group, list) ->
                val collapsed = group in collapsedGroups
                item(span = { GridItemSpan(maxLineSpan) }, key = "group:$group") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (collapsed) collapsedGroups.remove(group) else collapsedGroups.add(group)
                            }
                            .padding(top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group,
                            style = MiuixTheme.textStyles.headline2,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = if (collapsed) "${list.size} 张 · 已折叠" else "${list.size} 张",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = if (collapsed) MiuixIcons.ExpandMore else MiuixIcons.ExpandLess,
                            contentDescription = if (collapsed) "展开" else "折叠",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                if (!collapsed) {
                    items(items = list, key = { it.key }) { item ->
                        CatalogTile(
                            item = item,
                            localImage = repo.localImage(item),
                            favorite = repo.isFavorite(item),
                            onClick = { selected = item },
                            onFavorite = {
                                runCatching { repo.toggleFavorite(item) }
                                    .onFailure { scope.launch { model.notify(it.message ?: "收藏失败") } }
                                localRevision++
                            },
                        )
                    }
                }
            }

            if (mode == 0 && pageInfo != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                    Text(
                        text = when {
                            loading -> "正在加载更多…"
                            page >= (pageInfo?.pages ?: 1) -> "已经到底了"
                            else -> "上拉加载更多"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }

        // 右下角悬浮按钮：A 打开字母索引，上箭头回到顶部
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 20.dp,
                    bottom = innerPadding.calculateBottomPadding() + 20.dp,
                ),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (mode == 0 && groups.isNotEmpty()) {
                Card(
                    modifier = Modifier.size(58.dp),
                    cornerRadius = 29.dp,
                    insideMargin = PaddingValues(14.dp),
                    onClick = { showIndex = true },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "A",
                            style = MiuixTheme.textStyles.headline2,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (gridState.firstVisibleItemIndex > 3) {
                Card(
                    modifier = Modifier.size(58.dp),
                    cornerRadius = 29.dp,
                    insideMargin = PaddingValues(14.dp),
                    onClick = {
                        scope.launch { gridState.animateScrollToItem(0) }
                    },
                ) {
                    Icon(
                        imageVector = BackToTopArrow,
                        contentDescription = "回到顶部",
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }

    if (showIpJump) {
        OverlayDialog(
            show = true,
            title = "IP 跳转",
            summary = "点任意 IP / 分组直接跳过去",
            onDismissRequest = { showIpJump = false },
        ) {
            TextField(
                value = ipQuery,
                onValueChange = { ipQuery = it.take(60) },
                label = "搜索名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            val jumpList = groups.keys.filter {
                ipQuery.isBlank() || it.contains(ipQuery.trim(), ignoreCase = true)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    jumpList.forEach { name ->
                        val target = groupAnchors[name]
                        Card(
                            cornerRadius = 14.dp,
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.secondaryContainer,
                            ),
                            onClick = {
                                if (target != null) {
                                    showIpJump = false
                                    scope.launch { gridState.animateScrollToItem(target) }
                                }
                            },
                        ) {
                            Text(
                                text = name,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showIndex) {
        OverlayDialog(
            show = true,
            title = "按首字母查找",
            summary = "点字母跳到对应分组（灰色表示没有该首字母的分组）",
            onDismissRequest = { showIndex = false },
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PinyinSort.LETTERS.forEach { letter ->
                    val target = anchors[letter]
                    val enabled = target != null
                    Card(
                        modifier = Modifier.size(44.dp),
                        cornerRadius = 12.dp,
                        insideMargin = PaddingValues(0.dp),
                        onClick = {
                            if (target != null) {
                                showIndex = false
                                scope.launch { gridState.animateScrollToItem(target) }
                            }
                        },
                        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.secondaryContainer.copy(
                                alpha = if (enabled) 1f else 0.4f,
                            ),
                        ),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = letter.toString(),
                                style = MiuixTheme.textStyles.body2,
                                color = if (enabled) {
                                    MiuixTheme.colorScheme.onSurface
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val current = selected
    if (current != null) {
        OverlayDialog(
            show = true,
            title = current.title,
            summary = listOf(current.subtitle, current.source)
                .filter { it.isNotBlank() }
                .joinToString("\n"),
            onDismissRequest = {
                if (downloadState == null) selected = null
            },
        ) {
            AppAsyncImage(
                model = repo.localImage(current)?.let { Uri.fromFile(it).toString() }
                    ?: HibankCatalogSource.previewUrl(current.imageUrl),
                fallbackModel = current.imageUrl,
                contentDescription = current.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CardImaging.TARGET_RATIO)
                    .clip(RoundedCornerShape(12.dp)),
                backgroundBrush = cardThumbnailGradient(),
            )
            Spacer(Modifier.height(12.dp))
            if (downloadState != null) {
                Text(
                    text = downloadState.orEmpty(),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = "取消下载",
                    onClick = {
                        downloadJob?.cancel()
                        downloadJob = null
                        downloadState = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = if (repo.isFavorite(current)) "取消收藏" else "收藏",
                        onClick = {
                            runCatching { repo.toggleFavorite(current) }
                                .onFailure { scope.launch { model.notify(it.message ?: "收藏失败") } }
                            localRevision++
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "使用此卡面",
                        onClick = {
                            downloadState = "准备下载…"
                            downloadJob = scope.launch {
                                try {
                                    val file = withContext(Dispatchers.IO) {
                                        repo.download(current) { done, total ->
                                            downloadState = if (total > 0) {
                                                "下载 ${done * 100 / total}%"
                                            } else {
                                                "已下载 ${done / 1024} KiB"
                                            }
                                        }
                                    }
                                    localRevision++
                                    selected = null
                                    downloadState = null
                                    onUse(Uri.fromFile(file))
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (t: Throwable) {
                                    model.notify(t.message ?: "图片下载失败，请重试")
                                } finally {
                                    downloadState = null
                                    downloadJob = null
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 分类用扁平 tab（下划线），和上面的胶囊模式行区分开。 */
@Composable
private fun CatalogTabRow(
    items: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        items.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                modifier = Modifier
                    .clickable { onSelected(index) }
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .width(if (active) 26.dp else 3.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) MiuixTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun CatalogStatusLine(
    loading: Boolean,
    total: Int,
    cached: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (cached) {
            Icon(
                imageVector = MiuixIcons.CloudFill,
                contentDescription = "离线缓存",
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
        }
        Text(
            text = when {
                loading -> "正在加载…"
                total > 0 -> "共 $total 张${if (cached) " · 离线缓存" else ""}"
                else -> "共 0 张"
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun CatalogPillRow(
    items: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, label ->
            val active = index == selected
            Card(
                modifier = Modifier.weight(1f),
                cornerRadius = 16.dp,
                insideMargin = PaddingValues(0.dp),
                onClick = { onSelected(index) },
                colors = if (active) {
                    top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary,
                    )
                } else {
                    top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors()
                },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (active) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogTile(
    item: CatalogItem,
    localImage: java.io.File?,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(8.dp),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CardImaging.TARGET_RATIO)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            AppAsyncImage(
                model = localImage?.let { Uri.fromFile(it).toString() }
                    ?: HibankCatalogSource.thumbnailUrl(item.imageUrl),
                fallbackModel = item.imageUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                backgroundBrush = cardThumbnailGradient(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onFavorite) {
                    Icon(
                        imageVector = if (favorite) MiuixIcons.FavoritesFill else MiuixIcons.Favorites,
                        contentDescription = if (favorite) "取消收藏" else "收藏",
                        tint = if (favorite) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


/** 回到顶部的上箭头（Miuix 图标集里没有 ArrowUp，这里用 Material 的 ArrowUpward 路径自绘）。 */
private val BackToTopArrow: ImageVector = ImageVector.Builder(
    name = "BackToTopArrow",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathData {
        moveTo(4f, 12f)
        lineTo(5.41f, 13.41f)
        lineTo(11f, 7.83f)
        lineTo(11f, 20f)
        lineTo(13f, 20f)
        lineTo(13f, 7.83f)
        lineTo(18.58f, 13.42f)
        lineTo(20f, 12f)
        lineTo(12f, 4f)
        close()
    },
    fill = SolidColor(Color.Black),
).build()
