package com.dsh.miuix.cardface.core

import android.content.Context
import org.json.JSONObject

object WalletDiagnostics {
    fun report(context: Context): String {
        val wallet = WalletApps.primary(context) ?: return "未检测到支持的钱包应用。"
        val info = context.packageManager.getPackageInfo(wallet.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val raw = context.getSharedPreferences("runtime_health", Context.MODE_PRIVATE).getString(wallet.packageName, null)
        val health = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        return buildString {
            appendLine("钱包：${wallet.label}")
            appendLine("包名：${wallet.packageName}")
            appendLine("安装版本：${info.versionName}（$versionCode）")
            appendLine("适配基线：${if (wallet == WalletKind.ONEPLUS) "一加钱包 5.52.0" else "上游小米智能卡适配"}")
            if (health == null) {
                appendLine("检测结果：暂无本版 Hook 回报。请确认 LSPosed 作用域并重启钱包。")
                return@buildString
            }
            val at = health.optLong("at")
            val timestamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(at))
            val getters = health.optInt("cardGetters")
            val reportedVersion = health.optLong("walletVersionCode", -1)
            val moduleCurrent = health.optString("module") == BuildInfo.INTERNAL_VERSION
            val state = when {
                !moduleCurrent -> "钱包仍加载旧模块，需重启钱包"
                reportedVersion != versionCode -> "钱包已升级或回报版本未知，需重启后重新检测"
                health.optInt("hooks") <= 0 -> "关键 Hook 未安装，请查看缺失点"
                wallet == WalletKind.ONEPLUS && getters <= 0 -> "卡面模型入口未定位，当前版本需重新适配"
                else -> "Hook 入口已安装；实际图片加载结果以卡片命中回报为准"
            }
            appendLine("检测结果：$state")
            appendLine("框架：${health.optString("framework", "未知")}")
            appendLine("回报模块：${health.optString("module")}")
            appendLine("回报时间：$timestamp")
            appendLine("Hook 数：${health.optInt("hooks")}；卡面入口：$getters；图片入口：${health.optInt("loaders")}")
            val missing = health.optJSONArray("missing")
            if (missing != null && missing.length() > 0) {
                appendLine("缺失点（部分为可选入口）：")
                for (i in 0 until missing.length()) appendLine("• ${missing.optString(i)}")
            }
        }.trim()
    }
}
