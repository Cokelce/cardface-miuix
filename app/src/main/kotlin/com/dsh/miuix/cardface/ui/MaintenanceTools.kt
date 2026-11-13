package com.dsh.miuix.cardface.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.miuix.cardface.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun MaintenanceTools(model: AppModel, onInfo: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = model.context
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<BackupArchive.Plan?>(null) }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false; plan?.close() } }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val count = withContext(Dispatchers.IO) { CardBackup.export(context, model.prefs, model.store, uri) }
                onInfo("备份完成", "已导出 $count 张本地图片，以及卡面映射、手动名称、识别名称和黑名单。网络图片保存其地址，不离线下载。备份保存在你选择的位置。")
            } catch (t: Throwable) { onInfo("备份失败", (t.message ?: "文件读写异常") + "\n若目标位置出现不完整 ZIP，可删除后重试。") }
            finally { busy = false }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                var staged: BackupArchive.Plan? = null
                try {
                    val inspected = withContext(Dispatchers.IO) { CardBackup.inspect(context, uri).also { staged = it } }
                    if (alive) { plan = inspected; staged = null } else inspected.close()
                } finally { staged?.close() }
            } catch (t: Throwable) { onInfo("备份校验失败", t.message ?: "文件不是本应用备份") }
            finally { busy = false }
        }
    }
    Column {
        SmallTitle(text = "备份与兼容检测")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            cornerRadius = 20.dp, insideMargin = PaddingValues(vertical = 4.dp)) {
            ArrowPreference(title = "导出卡面备份", summary = if (busy) "正在处理…" else "图片、映射、卡名与黑名单 · ZIP",
                onClick = { if (!busy) export.launch("CardFace-backup-${System.currentTimeMillis()}.zip") })
            ArrowPreference(title = "从备份恢复", summary = "先校验再确认；合并现有数据，同地址配置以备份为准",
                onClick = { if (!busy) restore.launch(arrayOf("application/zip", "application/octet-stream")) })
            ArrowPreference(title = "钱包兼容检测", summary = "安装版本、Hook 回报、旧模块与缺失入口",
                onClick = { if (!busy) scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { WalletDiagnostics.report(context) }.getOrElse { "检测异常：${it.message}" } }
                    onInfo("钱包兼容检测", result)
                } })
            ArrowPreference(title = "整理与重新同步", summary = "保守合并同图变体；同步已保存图片，不删除原始地址",
                onClick = { if (!busy) scope.launch {
                    busy = true
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val before = model.prefs.allCardUrls().size
                            val removed = model.prefs.sanitizeCardUrls()
                            val grouped = model.prefs.cardFaces().size
                            val copied = CardSync.syncAll(context, model.prefs, model.store)
                            "记录 $before 条；清理替换图/杂图 $removed 条；整理显示 $grouped 张；同步本地图片 $copied 张。\n同图变体保留原始地址，手动名称优先。"
                        }
                        model.prefs.bumpRevision(); onInfo("整理与同步", result)
                    } catch (t: Throwable) { onInfo("整理失败", t.message ?: "同步异常") }
                    finally { busy = false }
                } })
        }
    }
    plan?.let { candidate ->
        OverlayDialog(show = true, title = "恢复已校验的备份？",
            summary = "${candidate.cardCount} 条卡面地址，${candidate.images.size} 张图片。\n将合并现有数据，相同地址的替换配置和名称以备份为准，不删除其他卡片；恢复后需重启钱包。",
            onDismissRequest = { candidate.close(); plan = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(text = "恢复", enabled = !busy, onClick = {
                    plan = null
                    scope.launch {
                        busy = true
                        try {
                            val count = withContext(Dispatchers.IO) { CardBackup.restore(context, model.prefs, model.store, candidate) }
                            model.bumpStore(); model.prefs.bumpRevision()
                            val sync = withContext(Dispatchers.IO) { runCatching { CardSync.syncAll(context, model.prefs, model.store) }.getOrDefault(0) }
                            onInfo("恢复完成", "已恢复/合并 $count 条地址，同步 $sync 张本地替换图片。原有其他卡片保留。请重启钱包后查看命中状态。")
                        } catch (t: Throwable) { onInfo("恢复失败", t.message ?: "存储异常，原有未涉及的卡片保留") }
                        finally { candidate.close(); busy = false }
                    }
                }, modifier = Modifier.weight(1f))
                TextButton(text = "取消", onClick = { candidate.close(); plan = null }, modifier = Modifier.weight(1f))
            }
        }
    }
}
