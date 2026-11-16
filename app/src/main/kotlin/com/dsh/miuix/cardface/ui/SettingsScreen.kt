package com.dsh.miuix.cardface.ui

import androidx.compose.foundation.layout.Column
import top.yukonga.miuix.kmp.basic.TextButton
import androidx.compose.foundation.layout.Box
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import com.dsh.miuix.cardface.core.SyncMode
import com.dsh.miuix.cardface.core.ThemeColors
import com.dsh.miuix.cardface.core.AppPrefs
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dsh.miuix.cardface.core.LsposedManager
import com.dsh.miuix.cardface.core.WalletApps
import com.dsh.miuix.cardface.core.WalletKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class ConfirmSpec(
    val title: String,
    val message: String,
    val confirmText: String,
    val destructive: Boolean = false,
    val onConfirm: () -> Unit,
)

/**
 * 设置页：分组卡片 + 真实能力（能接的都接到系统调用，没接线的标 TODO 并给出说明）。
 */
@Composable
fun SettingsScreen(
    model: AppModel,
    innerPadding: PaddingValues,
    onOpenBlacklist: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenFaq: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenVersion: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = model.prefs
    val scope = rememberCoroutineScope()
    val revision = prefs.revision

    val status = remember(revision) { runCatching { WalletApps.status(context, prefs) }.getOrNull() }
    val lsposedManager = remember(revision) { runCatching { LsposedManager.find(context) }.getOrNull() }
    val walletKind = status?.walletKind
    val versionName = remember { DeviceBridge.versionName(context) }

    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirm by remember { mutableStateOf<ConfirmSpec?>(null) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDarkDialog by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }

    fun toast(message: String) {
        scope.launch { model.notify(message) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 28.dp,
        ),
    ) {
        item(key = "header") {
            LargeTitleHeader(
                title = "设置",
                contentPadding = PaddingValues(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 8.dp),
            )
        }

        // ---------- 模块 ----------
        item(key = "module") {
            SettingsGroup("模块与卡包") {
                val moduleSummary = when {
                    status == null -> "读取失败"
                    status.lastHookAt <= 0L -> "未生效 · 打开卡包浏览卡面后自动上报"
                    (System.currentTimeMillis() - status.lastHookAt) < 24L * 60L * 60L * 1000L -> "已生效"
                    else -> "上次生效：${(((System.currentTimeMillis() - status.lastHookAt) / 86_400_000L)).toInt()} 天前"
                }
                ArrowPreference(
                    title = "模块状态",
                    startAction = { SettingRowIcon(MiuixIcons.Info) },
                    summary = moduleSummary,
                    onClick = {
                        val message = buildString {
                            appendLine("LSPosed 管理器：${if (status?.lsposedInstalled == true) "已安装" else "未检测到"}")
                            appendLine("卡包应用：${DeviceBridge.walletLabel(walletKind)}")
                            appendLine()
                            if (status?.lastHookAt != null && status.lastHookAt > 0L) {
                                appendLine("卡包进程最近一次上报：${formatTime(status.lastHookAt)}")
                            } else {
                                appendLine("卡包进程还没有上报过：先在 LSPosed 里启用模块并勾选作用域，再打开卡包浏览一遍卡面。")
                            }
                        }.trim()
                        info = "模块状态" to message
                    },
                )
                RowDivider()
                ArrowPreference(
                    title = "卡包应用",
                    startAction = { SettingRowIcon(MiuixIcons.BankCards) },
                    summary = DeviceBridge.walletLabel(walletKind),
                    onClick = {
                        if (walletKind == null) {
                            info = "没有找到卡包" to "本机没有安装小米智能卡，安装后才能替换卡面。"
                        } else if (!DeviceBridge.openWallet(context, walletKind)) {
                            info = "打不开卡包" to "系统没有返回 ${walletKind.label} 的启动入口，请手动打开。"
                        }
                    },
                )
                RowDivider()
                ArrowPreference(
                    title = "LSPosed 作用域",
                    startAction = { SettingRowIcon(MiuixIcons.Link) },
                    summary = when {
                        lsposedManager != null && (status?.lastHookAt ?: 0L) > 0L ->
                            "管理器：${lsposedManager.label} · 作用域已勾选"
                        lsposedManager != null ->
                            "管理器：${lsposedManager.label} · 请在 LSPosed 里勾选小米智能卡"
                        status?.lsposedInstalled == true ->
                            "寄生/隐藏模式 · 从 LSPosed 通知打开管理器"
                        else -> "未检测到 LSPosed 管理器"
                    },
                    onClick = {
                        info = "启用模块与作用域" to buildString {
                            appendLine("1. 打开你的 LSPosed 管理器（本机可能是寄生 / 隐藏模式，桌面没有图标）。")
                            appendLine("2. 在模块列表里启用「卡面工坊」。")
                            appendLine("3. 勾选作用域：小米智能卡（com.miui.tsmclient）。")
                            appendLine("4. 强行停止小米智能卡，再打开它把卡面浏览一遍。")
                            appendLine("5. 回到卡面工坊下拉刷新，就能看到卡面了。")
                            appendLine()
                            append("检测到的管理器：")
                            append(
                                lsposedManager?.let { "${it.label}（${it.packageName}）" }
                                    ?: if (status?.lsposedInstalled == true) {
                                        "寄生/隐藏模式（没有可直接启动的入口，请从通知进入）"
                                    } else {
                                        "未检测到"
                                    },
                            )
                        }
                    },
                )
            }
        }

        // ---------- 卡面显示 ----------
        item(key = "display") {
            SettingsGroup("卡面显示") {
                SwitchPreference(
                    title = "隐藏桌面图标",
                    startAction = { SettingRowIcon(MiuixIcons.Hide) },
                    summary = "从桌面隐藏，只能从 LSPosed 模块设置进入",
                    checked = prefs.hideIcon,
                    onCheckedChange = { checked ->
                        if (checked) {
                            confirm = ConfirmSpec(
                                title = "隐藏桌面图标？",
                                message = "隐藏后这个应用会从桌面消失，只能从 LSPosed 管理器里的模块设置进入，避免误触。",
                                confirmText = "隐藏",
                                onConfirm = {
                                    prefs.hideIcon = true
                                    val ok = DeviceBridge.setLauncherAliasVisible(context, false)
                                    if (ok) {
                                        com.dsh.miuix.cardface.core.PackageOps.postHiddenEntryNotification(context)
                                        toast("已隐藏桌面图标（通知栏有入口）")
                                    } else {
                                        // 没真正隐藏成功就不要把设置留在“已隐藏”，否则下次启动自愈会一直重试
                                        prefs.hideIcon = false
                                        com.dsh.miuix.cardface.core.PackageOps.setLauncherVisible(context, true)
                                        toast("隐藏失败：组件状态未更新，已恢复显示")
                                    }
                                },
                            )
                        } else {
                            prefs.hideIcon = false
                            DeviceBridge.setLauncherAliasVisible(context, true)
                            com.dsh.miuix.cardface.core.PackageOps.cancelHiddenEntryNotification(context)
                            toast("已恢复桌面图标")
                        }
                    },
                )
                if (walletKind == WalletKind.MIUI) {
                    RowDivider()
                    SwitchPreference(
                        title = "包含小米超级岛卡面",
                    startAction = { SettingRowIcon(MiuixIcons.Show) },
                        summary = "关闭后不拦截超级岛里的卡面",
                        checked = prefs.superIsland,
                        onCheckedChange = { prefs.superIsland = it },
                    )
                }
                RowDivider()
                SwitchPreference(
                    title = "显示全部卡面",
                    startAction = { SettingRowIcon(MiuixIcons.Image) },
                    summary = "关闭时会过滤小图（w270h480 / logo / Mibi）",
                    checked = prefs.showAllCards,
                    onCheckedChange = { prefs.showAllCards = it },
                )
                RowDivider()
                SwitchPreference(
                    title = "显示卡面库入口",
                    startAction = { SettingRowIcon(MiuixIcons.Store) },
                    summary = "在线卡面库（Hibank 图片源），关闭后首页/编辑页隐藏入口",
                    checked = prefs.catalogEnabled,
                    onCheckedChange = { prefs.catalogEnabled = it },
                )
                RowDivider()
                SwitchPreference(
                    title = "省流量模式",
                    startAction = { SettingRowIcon(MiuixIcons.CloudFill) },
                    summary = "Wi-Fi 下加载原图；移动网络用压缩缩略图，省流量但稍糊",
                    checked = prefs.dataSaver,
                    onCheckedChange = { prefs.dataSaver = it },
                )
                RowDivider()
                SwitchPreference(
                    title = "保存到图库前命名",
                    startAction = { SettingRowIcon(MiuixIcons.Rename) },
                    summary = "开启后，从相册选图保存到图库时会先输入名称；关闭则自动命名",
                    checked = prefs.askNameOnGallerySave,
                    onCheckedChange = { prefs.askNameOnGallerySave = it },
                )
                RowDivider()
                ArrowPreference(
                    title = "主界面卡面黑名单",
                    startAction = { SettingRowIcon(MiuixIcons.Blocklist) },
                    summary = "已加入 ${prefs.blacklistedUrls().size} 张",
                    onClick = onOpenBlacklist,
                )
            }
        }

        // ---------- 外观 ----------
        item(key = "appearance") {
            SettingsGroup("外观") {
                SwitchPreference(
                    title = "动态取色",
                    startAction = { SettingRowIcon(MiuixIcons.Theme) },
                    summary = "跟随壁纸取色（HyperOS 风格）；关闭后使用主题色",
                    checked = prefs.dynamicColor,
                    onCheckedChange = { prefs.dynamicColor = it },
                )
                RowDivider()
                val presetName = ThemeColors.presets
                    .firstOrNull { it.color.toArgb() == prefs.keyColorArgb }?.name ?: "自定义"
                ArrowPreference(
                    title = if (prefs.dynamicColor) "主题色" else "主题色 · $presetName",
                    summary = if (prefs.dynamicColor) {
                        "已开启动态取色，关闭后才能选主题色"
                    } else {
                        "当前：$presetName"
                    },
                    enabled = !prefs.dynamicColor,
                    startAction = {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color(prefs.keyColorArgb), CircleShape),
                        )
                    },
                    onClick = { showThemeDialog = true },
                )
                RowDivider()
                ArrowPreference(
                    title = "深色模式",
                    startAction = { SettingRowIcon(MiuixIcons.Background) },
                    summary = when (prefs.darkMode) {
                        1 -> "浅色"
                        2 -> "深色"
                        else -> "跟随系统"
                    },
                    onClick = { showDarkDialog = true },
                )
                RowDivider()
                SwitchPreference(
                    title = "界面过渡动画",
                    startAction = { SettingRowIcon(MiuixIcons.Play) },
                    summary = "关闭后页面切换、列表动画立即失效",
                    checked = prefs.animations,
                    onCheckedChange = { prefs.animations = it },
                )
                RowDivider()
                ArrowPreference(
                    title = "恢复默认外观",
                    startAction = { SettingRowIcon(MiuixIcons.Reset) },
                    summary = "澎湃蓝主题 + 跟随系统 + 开启动画",
                    onClick = {
                        prefs.dynamicColor = false
                        prefs.keyColorArgb = AppPrefs.DEFAULT_KEY_COLOR
                        prefs.darkMode = 0
                        prefs.animations = true
                        toast("已恢复默认外观")
                    },
                )
            }
        }

        // ---------- 缓存与修复 ----------
        item(key = "cache") {
            SettingsGroup("数据与维护") {
                ArrowPreference(
                    title = "清除 hook 点缓存",
                    startAction = { SettingRowIcon(MiuixIcons.Refresh) },
                    summary = "重新定位卡包里被混淆的方法（需要重启卡包）",
                    onClick = {
                        confirm = ConfirmSpec(
                            title = "清除 hook 点缓存？",
                            message = "会删除卡包进程里的方法定位缓存并强制停止卡包。这一步需要 root 权限。",
                            confirmText = "清除",
                            onConfirm = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        DeviceBridge.clearHookCache(context, walletKind)
                                    }
                                    if (ok) {
                                        toast("已清除 hook 点缓存并重启卡包")
                                    } else {
                                        info = "清除失败" to "没有清掉的话通常是没给 root 权限，或者本机没有安装卡包。可以打开 root 管理器授权后再试一次。"
                                    }
                                }
                            },
                        )
                    },
                )
                RowDivider()
                ArrowPreference(
                    title = "清除图片缓存",
                    startAction = { SettingRowIcon(MiuixIcons.Image) },
                    summary = "清掉缩略图缓存，不会删除图库里的替换图",
                    onClick = {
                        confirm = ConfirmSpec(
                            title = "清除图片缓存？",
                            message = "只清理缓存文件，图库里保存的替换图不受影响。清理后建议重启本应用。",
                            confirmText = "清除",
                            onConfirm = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { DeviceBridge.clearImageCaches(context) }
                                    toast(if (ok) "图片缓存已清理，重启本应用后生效" else "清理失败")
                                }
                            },
                        )
                    },
                )
                RowDivider()
                ArrowPreference(
                    title = "清空所有卡面记录",
                    startAction = { SettingRowIcon(MiuixIcons.Delete) },
                    summary = "已识别 ${prefs.allCardUrls().size} 张；替换图本身不会被删除",
                    onClick = {
                        confirm = ConfirmSpec(
                            title = "清空所有卡面记录？",
                            message = "会清掉首页的卡面列表和全部替换映射（图库里的图片仍然保留）。下次打开卡包浏览后会自动重新识别。",
                            confirmText = "清空",
                            destructive = true,
                            onConfirm = {
                                prefs.clearCardUrls()
                                com.dsh.miuix.cardface.core.CardSync.writeMapSnapshot(context, prefs)
                                        com.dsh.miuix.cardface.core.DemoCards.clearImages(model.store)
                                model.bumpStore()
                                toast("已清空卡面记录，打开卡包浏览后会重新识别")
                            },
                        )
                    },
                )
            }
        }

        // ---------- 关于 ----------
        item(key = "about") {
            SettingsGroup("关于") {
                ArrowPreference(
                    title = "版本 v$versionName",
                    summary = "内部 ${com.dsh.miuix.cardface.core.BuildInfo.INTERNAL_VERSION} · 检查更新 / 更新公告",
                    onClick = onOpenVersion,
                )
                RowDivider()
                ArrowPreference(title = "使用说明", onClick = onOpenGuide)
                RowDivider()
                ArrowPreference(title = "常见问题", onClick = onOpenFaq)
                RowDivider()
                ArrowPreference(
                    title = "关于应用",
                    startAction = { SettingRowIcon(MiuixIcons.Info) },
                    summary = "版本信息 · 开源许可 · 免责声明",
                    onClick = onOpenAbout,
                )
            }
        }

        item(key = "footer") {
            Text(
                text = "卡面工坊 · Miuix 重制版\n不收集任何数据，替换图只存在本机",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }

    val infoValue = info
    if (infoValue != null) {
        InfoDialog(
            show = true,
            title = infoValue.first,
            message = infoValue.second,
            onDismiss = { info = null },
        )
    }

    val confirmValue = confirm
    if (confirmValue != null) {
        ConfirmDialog(
            show = true,
            title = confirmValue.title,
            message = confirmValue.message,
            confirmText = confirmValue.confirmText,
            destructive = confirmValue.destructive,
            onConfirm = {
                confirm = null
                confirmValue.onConfirm()
            },
            onDismiss = { confirm = null },
        )
    }

    // 主题色选择
    if (showThemeDialog) {
        OverlayDialog(
            show = true,
            title = "主题色",
            summary = "选中立即生效；动态取色关闭时才使用这里的颜色。",
            onDismissRequest = { showThemeDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeColors.presets.chunked(4).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowItems.forEach { preset ->
                            ThemeColorOption(
                                preset = preset,
                                selected = prefs.keyColorArgb == preset.color.toArgb(),
                                onClick = {
                                    prefs.keyColorArgb = preset.color.toArgb()
                                    showThemeDialog = false
                                    toast("主题色已切换为「${preset.name}」")
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            TextButton(
                text = "关闭",
                onClick = { showThemeDialog = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 映射同步方式选择
    if (showSyncDialog) {
        OverlayDialog(
            show = true,
            title = "映射同步方式",
            summary = "自动：本地 + LSPosed 远程双写（推荐）；仅远程：只写 LSPosed；仅本地：只写本地。切换后重启卡包生效。",
            onDismissRequest = { showSyncDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncMode.entries.forEach { mode ->
                    val selected = prefs.syncMode == mode
                    TextButton(
                        text = if (selected) "✓  ${mode.label}" else mode.label,
                        onClick = {
                            prefs.syncMode = mode
                            showSyncDialog = false
                            com.dsh.miuix.cardface.core.CardSync.writeMapSnapshot(context, prefs)
                            toast("同步方式已切换：${mode.label}，重启卡包后生效")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (selected) {
                            ButtonDefaults.textButtonColorsPrimary()
                        } else {
                            ButtonDefaults.textButtonColors()
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(
                text = "关闭",
                onClick = { showSyncDialog = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 深色模式选择
    if (showDarkDialog) {
        OverlayDialog(
            show = true,
            title = "深色模式",
            summary = "「跟随系统」会自动跟随手机的深色模式开关。",
            onDismissRequest = { showDarkDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DARK_MODE_OPTIONS.forEach { (value, label) ->
                    val selected = prefs.darkMode == value
                    TextButton(
                        text = if (selected) "✓  $label" else label,
                        onClick = {
                            prefs.darkMode = value
                            showDarkDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (selected) {
                            ButtonDefaults.textButtonColorsPrimary()
                        } else {
                            ButtonDefaults.textButtonColors()
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(
                text = "关闭",
                onClick = { showDarkDialog = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SmallTitle(text = title)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            cornerRadius = 20.dp,
            content = content,
        )
        Spacer(Modifier.height(12.dp))
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "从未"
    return runCatching {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
    }.getOrDefault("未知")
}

private val DARK_MODE_OPTIONS = listOf(
    0 to "跟随系统",
    1 to "浅色",
    2 to "深色",
)

@Composable
private fun ThemeColorOption(
    preset: ThemeColors.Preset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(preset.color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = "当前主题色",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = preset.name,
            style = MiuixTheme.textStyles.footnote2,
            color = if (selected) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            maxLines = 1,
        )
    }
}

/** 设置行左侧的小图标（HyperOS 风格：22dp，深色文字色）。 */
@Composable
private fun SettingRowIcon(image: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = image,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.size(22.dp),
    )
}
