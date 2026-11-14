package com.dsh.miuix.cardface.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.miuix.cardface.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun OnlineGalleryScreen(model:AppModel, innerPadding:PaddingValues, onBack:()->Unit, onUse:(Uri)->Unit) {
    val repo=remember{OnlineRepository(model.context)}
    val scope=rememberCoroutineScope()
    var categoryIndex by rememberSaveable{mutableIntStateOf(0)}
    var mode by rememberSaveable{mutableIntStateOf(0)}
    var search by rememberSaveable{mutableStateOf("")}
    var filter by rememberSaveable{mutableStateOf("")}
    var appliedSearch by rememberSaveable{mutableStateOf("")}
    var appliedFilter by rememberSaveable{mutableStateOf("")}
    var page by rememberSaveable{mutableIntStateOf(1)}
    var refresh by remember{mutableIntStateOf(0)}
    var localRevision by remember{mutableIntStateOf(0)}
    var result by remember{mutableStateOf<OnlinePage?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var loading by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<OnlineCard?>(null)}
    var download by remember{mutableStateOf<String?>(null)}
    var job by remember{mutableStateOf<kotlinx.coroutines.Job?>(null)}
    val category=OnlineCategory.entries[categoryIndex]
    val gridState=rememberLazyGridState()
    LaunchedEffect(categoryIndex,page,appliedSearch,appliedFilter,mode,refresh) {
        result=null;error=null
        if(mode!=0){loading=false;return@LaunchedEffect}
        loading=true
        try { result=withContext(Dispatchers.IO){repo.load(category,page,appliedSearch,appliedFilter)} }
        catch(c:CancellationException){throw c}
        catch(t:Throwable){error=t.message ?: "目录加载失败，请重试"}
        finally{loading=false}
        gridState.scrollToItem(0)
    }
    val cards=if(mode==0)result?.cards.orEmpty() else remember(mode,localRevision){if(mode==1)repo.favorites() else repo.recent()}
    fun openSite(){runCatching{model.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,Uri.parse(category.webUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}}
    Column(Modifier.fillMaxSize().padding(bottom=innerPadding.calculateBottomPadding())) {
        LazyVerticalGrid(columns=GridCells.Adaptive(150.dp),state=gridState,modifier=Modifier.weight(1f),
            contentPadding=PaddingValues(start=16.dp,end=16.dp,top=innerPadding.calculateTopPadding(),bottom=16.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(span={GridItemSpan(maxLineSpan)},key="head") {BackHeader(title="在线卡面",onBack=onBack)}
            item(span={GridItemSpan(maxLineSpan)},key="source") {
                Column {
                    Text("来源：Hibank · 按需加载，选中后下载到本地",style=MiuixTheme.textStyles.footnote1,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text("已应用图片独立保存，清理浏览缓存不影响替换图。",style=MiuixTheme.textStyles.footnote2,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf("浏览","收藏","最近使用").forEachIndexed { i,label ->
                            TextButton(text=if(mode==i)"• $label" else label,onClick={mode=i;page=1},modifier=Modifier.weight(1f))
                        }
                    }
                }
            }
            if(mode==0) {
                item(span={GridItemSpan(maxLineSpan)},key="categories") {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        OnlineCategory.entries.forEachIndexed { i,c ->
                            TextButton(text=if(categoryIndex==i)"• ${c.label}" else c.label,
                                onClick={categoryIndex=i;page=1;filter="";appliedFilter=""},modifier=Modifier.weight(1f))
                        }
                    }
                }
                item(span={GridItemSpan(maxLineSpan)},key="search") {
                    Card(cornerRadius=18.dp,insideMargin=PaddingValues(12.dp)) {
                        TextField(value=search,onValueChange={search=it.take(120)},label="搜索卡面名称",singleLine=true,modifier=Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        TextField(value=filter,onValueChange={filter=it.take(120)},label=category.filterLabel,singleLine=true,modifier=Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(text="重置",onClick={search="";filter="";appliedSearch="";appliedFilter="";page=1},modifier=Modifier.weight(1f))
                            TextButton(text="搜索",onClick={appliedSearch=search.trim();appliedFilter=filter.trim();page=1;refresh++},modifier=Modifier.weight(1f))
                        }
                    }
                }
            }
            item(span={GridItemSpan(maxLineSpan)},key="count") {
                Text(if(loading)"正在加载目录…" else if(mode==0)"${result?.total ?: 0} 张 · 第 $page / ${result?.pages ?: 1} 页${if(result?.cached==true)" · 离线缓存" else ""}" else "${cards.size} 张",
                    style=MiuixTheme.textStyles.footnote1,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if(error!=null) item(span={GridItemSpan(maxLineSpan)},key="error") {
                Column {
                    Text(error.orEmpty(),color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row {TextButton(text="重试",onClick={refresh++});TextButton(text="打开原网站",onClick={openSite()})}
                }
            }
            if(!loading && error==null && cards.isEmpty()) item(span={GridItemSpan(maxLineSpan)},key="empty") {
                Text(if(mode==1)"还没有收藏。点开卡面即可收藏。" else if(mode==2)"使用过的在线卡面会显示在这里。" else "没有找到匹配的卡面，试试更短的关键词。",modifier=Modifier.padding(vertical=20.dp))
            }
            items(cards,key={it.key}) {card ->
                Card(cornerRadius=16.dp,insideMargin=PaddingValues(8.dp),onClick={selected=card}) {
                    AppAsyncImage(model=repo.cachedLocal(card) ?: card.imageUrl,contentDescription=card.title,
                        modifier=Modifier.fillMaxWidth().aspectRatio(CardImaging.TARGET_RATIO).clip(RoundedCornerShape(10.dp)),backgroundBrush=cardThumbnailGradient())
                    Spacer(Modifier.height(8.dp))
                    Text(card.title,style=MiuixTheme.textStyles.body2,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(card.subtitle,style=MiuixTheme.textStyles.footnote2,color=MiuixTheme.colorScheme.onSurfaceVariantSummary,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            if(mode==0 && result!=null) item(span={GridItemSpan(maxLineSpan)},key="pages") {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    TextButton(text="上一页",enabled=page>1&&!loading,onClick={page--},modifier=Modifier.weight(1f))
                    TextButton(text="下一页",enabled=page<(result?.pages?:1)&&!loading,onClick={page++},modifier=Modifier.weight(1f))
                }
            }
            item(span={GridItemSpan(maxLineSpan)},key="footer") {
                TextButton(text="Hibank 原网站 · 查看来源",onClick={openSite()},modifier=Modifier.fillMaxWidth())
            }
        }
    }
    selected?.let {card ->
        OverlayDialog(show=true,title=card.title,summary=listOf(card.subtitle,card.source,"来源：Hibank · 图片及标识归原权利人所有").filter{it.isNotBlank()}.joinToString("\n"),onDismissRequest={selected=null}) {
            AppAsyncImage(model=repo.cachedLocal(card) ?: card.imageUrl,contentDescription="卡面大图预览",
                modifier=Modifier.fillMaxWidth().aspectRatio(CardImaging.TARGET_RATIO).clip(RoundedCornerShape(12.dp)),backgroundBrush=cardThumbnailGradient())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(text=if(repo.isFavorite(card))"取消收藏" else "收藏",onClick={runCatching{repo.toggleFavorite(card)}.onFailure{scope.launch{model.notify(it.message ?: "收藏失败")}};localRevision++},modifier=Modifier.weight(1f))
                TextButton(text="使用此卡面",onClick={
                    selected=null
                    job=scope.launch {
                        download="准备下载原图…"
                        try {
                            val file=withContext(Dispatchers.IO){repo.download(card){done,total->scope.launch{download=if(total>0)"下载 ${done*100/total}%" else "已下载 ${done/1024} KiB"}}}
                            model.bumpStore();localRevision++;download=null
                            onUse(Uri.fromFile(file))
                        }catch(c:CancellationException){throw c}
                        catch(t:Throwable){model.notify(t.message ?: "图片下载失败，请重试")}
                        finally{download=null}
                    }
                },enabled=job?.isActive!=true,modifier=Modifier.weight(1f))
            }
        }
    }
    if(download!=null) OverlayDialog(show=true,title="下载卡面",summary=download.orEmpty()+"\n校验图片后保存本地，再进入裁剪。不直接使用远程地址替换。",onDismissRequest={job?.cancel()}) {
        TextButton(text="取消",onClick={job?.cancel()})
    }
}
