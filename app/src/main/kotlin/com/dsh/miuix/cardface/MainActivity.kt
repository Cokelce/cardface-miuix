package com.dsh.miuix.cardface

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 卡面库缩略图并发：Coil 默认并发较低，滚动时后面的图排队很慢。
        // OkHttpNetworkFetcher.factory 不是公开 Kotlin API，这里用反射拿到工厂，失败则保持默认加载器。
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = 24
                        maxRequestsPerHost = 12
                    },
                )
                .build()
            val fetcherClass = Class.forName("coil3.network.okhttp.OkHttpNetworkFetcher")
            val factoryMethod = fetcherClass.getMethod("factory", okhttp3.Call.Factory::class.java)
            val factory = factoryMethod.invoke(null, client) as coil3.network.NetworkFetcher.Factory
            val loader = coil3.ImageLoader.Builder(this)
                .components { add(factory) }
                .build()
            coil3.SingletonImageLoader.setUnsafe(loader)
        } catch (_: Throwable) {
        }
        // 初始化 LSPosed RemotePreferences 通道，并把本地映射迁移过去（异步、失败不影响使用）。
        try {
            com.dsh.miuix.cardface.core.RemotePrefsBridge.init()
            com.dsh.miuix.cardface.core.RemotePrefsBridge.migrateIfNeeded(this)
        } catch (_: Throwable) {
        }
        // 自愈：组件状态与设置不一致时修正；隐藏状态下保证通知入口存在。
        try {
            val prefs = com.dsh.miuix.cardface.core.AppPrefs(this)
            com.dsh.miuix.cardface.core.PackageOps.ensureLauncherMatchesPrefs(this, prefs.hideIcon)
            cleanupTestAssets(prefs)
            if (prefs.hideIcon) {
                com.dsh.miuix.cardface.core.PackageOps.postHiddenEntryNotification(this)
            }
        } catch (_: Throwable) {
        }
        setContent { CardFaceApp() }
    }

    /** 一次性清掉历史版本自动生成的测试图 / 演示图，正式包图库保持干净。 */
    private fun cleanupTestAssets(prefs: com.dsh.miuix.cardface.core.AppPrefs) {
        val marker = getSharedPreferences("maintenance", MODE_PRIVATE)
        if (marker.getBoolean("test_assets_cleaned_v1", false)) return
        runCatching {
            val store = com.dsh.miuix.cardface.core.ImageStore(this)
            store.dir.listFiles { f ->
                f.isFile && (f.name == "cardface-test.png" || f.name.startsWith("demo-card-"))
            }?.forEach { it.delete() }
            val demoUrls = prefs.allCardUrls().filter { it.startsWith("https://demo.cardface.local/") }
            prefs.removeCardUrls(demoUrls)
            marker.edit().putBoolean("test_assets_cleaned_v1", true).apply()
        }
    }
}
