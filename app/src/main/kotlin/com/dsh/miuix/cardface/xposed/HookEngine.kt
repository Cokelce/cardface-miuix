package com.dsh.miuix.cardface.xposed

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import com.dsh.miuix.cardface.core.RootShell
import java.lang.reflect.Method

/**
 * 单个卡包进程里的 hook 运行时：
 *  - 读设置（framework remote prefs + 模块 provider 合并）
 *  - 定位 hook 点并安装拦截器（DexKit / 缓存 / 自定义 / 反射兜底）
 *  - 执行 replaceUrl（UrlMapper）+ 写回 card_urls / last_hook_at
 */
class HookEngine(
    private val module: XposedModule,
    private val osType: Int,
    private val classLoader: ClassLoader,
    private val appInfo: ApplicationInfo,
) {

    private val settingsRepo = SettingsRepository(module)
    private val locator = MethodLocator(osType, classLoader, appInfo)

    private val walletPackage: String = appInfo.packageName

    private val walletDataDir: String = appInfo.dataDir ?: "/data/data/$walletPackage"

    private val walletImagesDir: String = "$walletDataDir/files/images"

    private val moduleDataDir: String by lazy {
        val pkg = module.moduleApplicationInfo.packageName
        "/data/data/$pkg"
    }

    @Volatile
    private var installedCount = 0
    private var cardGetterCount = 0
    private var loaderCount = 0
    private val missingTargets = java.util.Collections.synchronizedList(ArrayList<String>())

    /** 已安装的 hook 数量（供入口判断是否需要重试）。 */
    val installedHooks: Int get() = installedCount

    @Volatile
    private var contextReady = false
    @Volatile private var contextForHealth: Context? = null

    @Volatile
    private var rootRetryAt = 0L

    private var lastCompatWarnMs = 0L

    private val contextHookers: MutableList<XposedInterface.HookHandle> = ArrayList()

    /** 自定义 hook 点需要 provider 里的开关；Context 还没到时先记下，拿到 Context 再装。 */
    @Volatile
    private var pendingCustomHook = false

    fun start() {
        if (osType == MethodLocator.OS_ONEPLUS) {
            runCatching { DetailCoverHook(module, classLoader).install() }
                .onFailure { XLog.w("detail cover hook install failed", it) }
        }
        XLog.i("cardface hook starting: osType=$osType package=$walletPackage api=${module.apiVersion}")
        settingsRepo.init()

        // 1. 先拿 Context：provider 读写（last_hook_at / card_urls / 设置）需要。
        installContextHook()
        if (osType == MethodLocator.OS_ONEPLUS) installWalletResumeHook()

        // 2. 自动 hook 点永远装（自愈；自定义 hook 点是额外的兜底，不冲突——两者都走 replaceUrl）。
        val settings = settingsRepo.snapshot()
        installAutoHooks(settings)

        // 3. 自定义 hook 点：settings 里的 class/method 配置。
        val customConfigured = settings.customClass.isNotEmpty() && settings.customMethod.isNotEmpty()
        if (customConfigured) {
            if (contextReady) {
                installCustomHook(settings)
            } else {
                pendingCustomHook = true
            }
        }
        locator.close()

        reportHealth()
        XLog.i("cardface hook installed hooks=$installedCount for $walletPackage")
        if (installedCount > 0) {
            settingsRepo.touchLastHookAt(force = true)
        } else {
            XLog.e("no hook point installed for $walletPackage, check settings / DexKit / LSPosed scope")
        }
    }

    private fun reportHealth() {
        val info = org.json.JSONObject()
            .put("at", System.currentTimeMillis()).put("module", com.dsh.miuix.cardface.core.BuildInfo.INTERNAL_VERSION)
            .put("hooks", installedCount).put("cardGetters", cardGetterCount).put("loaders", loaderCount)
            .put("framework", "${module.frameworkName} ${module.frameworkVersion}")
            .put("missing", org.json.JSONArray(missingTargets.toList()))
        try {
            val app = Application::class.java.getMethod("getProcessName")
            info.put("process", app.invoke(null)?.toString().orEmpty())
        } catch (_: Throwable) { }
        runCatching {
            val ctx = contextForHealth ?: return@runCatching null
            val pi = ctx.packageManager.getPackageInfo(walletPackage, 0)
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            info.put("walletVersion", pi.versionName).put("walletVersionCode", code)
        }
        settingsRepo.queueRuntime("runtime_health", walletPackage, info.toString())
    }

    // ---------- Context 捕获 ----------

    private val resumeRefreshAt = java.util.concurrent.atomic.AtomicLong(0L)

    private fun installWalletResumeHook() {
        tryHook("Instrumentation.callActivityOnResume") {
            Instrumentation::class.java.getDeclaredMethod("callActivityOnResume", Activity::class.java)
        }
    }

    private fun installContextHook() {
        tryHook("Application.attach") {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        }?.let { return }
        tryHook("Instrumentation.callApplicationOnCreate") {
            Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        }?.let { return }
        tryHook("Instrumentation.callActivityOnResume") {
            Instrumentation::class.java.getDeclaredMethod("callActivityOnResume", Activity::class.java)
        }
        tryHook("ContextWrapper.attachBaseContext") {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        }
        if (!contextReady) {
            XLog.w("failed to install any context hook; replaceUrl writes will be skipped")
        }
    }

    private fun tryHook(label: String, executable: () -> Method): Unit? {
        return try {
            val method = executable()
            val handle = module.hook(method).intercept(
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        try {
                            if (label == "Instrumentation.callActivityOnResume") refreshOwnedSnapshot()
                            if (label.startsWith("ContextWrapper")) {
                                val target = chain.thisObject
                                if (target is Application) {
                                    (chain.getArg(0) as? Context)?.let { onContextReady(it) }
                                }
                            } else {
                                (chain.getArg(0) as? Context)?.let { onContextReady(it) }
                            }
                        } catch (t: Throwable) {
                            XLog.w("$label: context capture failed", t)
                        }
                        return result
                    }
                },
            )
            contextHookers.add(handle)
            XLog.i("context hook installed: $label")
            Unit
        } catch (t: Throwable) {
            XLog.w("$label not available: $t")
            null
        }
    }

    private fun onContextReady(context: Context) {
        if (contextReady) return
        contextReady = true
        val appContext = context.applicationContext ?: context
        contextForHealth = appContext
        // 不要阻塞卡包的启动线程：provider 初始化 / DexKit 查询 / 写回都放后台线程。
        try {
            Thread({
                try {
                    settingsRepo.attachContext(appContext)
                    reportHealth()
                } catch (t: Throwable) {
                    XLog.w("attach context failed", t)
                }
                if (pendingCustomHook) {
                    pendingCustomHook = false
                    try {
                        val settings = settingsRepo.snapshot()
                        if (settings.customClass.isNotEmpty() && settings.customMethod.isNotEmpty()) {
                            installCustomHook(settings)
                            locator.close()
                        }
                    } catch (t: Throwable) {
                        XLog.w("install pending custom hook failed", t)
                    }
                }
                if (installedCount > 0) settingsRepo.touchLastHookAt(force = true)
            }, "cardface-context").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            XLog.w("start context thread failed", t)
        }
    }

    // ---------- hook 点安装 ----------

    private fun installCustomHook(settings: HookSettings) {
        if (settings.customClass.isEmpty() || settings.customMethod.isEmpty()) return
        val method = locator.findCustom(settings.customClass, settings.customMethod, settings.customMethodArg)
        if (method == null) {
            XLog.e(
                "custom hook point failed: ${settings.customClass}#${settings.customMethod}" +
                    " arg=${settings.customMethodArg}",
            )
            return
        }
        install(
            method = method,
            label = "custom(-1)",
            loadUrlFromArg = settings.loadUrlFromArg,
            replaceUrlToArg = settings.replaceUrlToArg,
            trackNewUrls = true,
        )
    }

    private fun installAutoHooks(settings: HookSettings) {
        if (osType == MethodLocator.OS_ONEPLUS) {
            installOnePlusHooks()
            return
        }
        val slots = ArrayList<Int>()
        slots.add(MethodLocator.SLOT_URL_FROM_ARG)
        slots.add(MethodLocator.SLOT_REPLACE_ARG)
        if (settings.superLand) slots.add(MethodLocator.SLOT_SUPER_ISLAND)
        val found = locator.findTargets(slots)
        for (slot in slots) {
            if (slot == MethodLocator.SLOT_REPLACE_ARG) {
                // Glide RequestManager 可能有多个 String 重载：全部装上，哪个被调用都能替换。
                val candidates = locator.findCandidates(slot)
                if (candidates.isNotEmpty()) {
                    val (loadUrlFromArg, replaceUrlToArg) = flagsForSlot(slot)
                    candidates.forEachIndexed { index, method ->
                        // 通用 Glide 加载：只做替换，不记录 URL（避免把图标/横幅当成卡面）。
                        install(method, label = "slot=$slot/$index", loadUrlFromArg, replaceUrlToArg, trackNewUrls = false)
                    }
                }
                continue
            }
            val method = found[slot] ?: continue
            val (loadUrlFromArg, replaceUrlToArg) = flagsForSlot(slot)
            install(method, label = "slot=$slot", loadUrlFromArg, replaceUrlToArg, trackNewUrls = true)
        }
    }

    private val ownedSnapshotAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val emptyOwnedSnapshotStreak = java.util.concurrent.atomic.AtomicInteger(0)

    private fun refreshOwnedSnapshot() {
        val now = System.currentTimeMillis()
        if (now - resumeRefreshAt.get() < 1500L || !resumeRefreshAt.compareAndSet(resumeRefreshAt.get(), now)) return
        runCatching {
            val manager = classLoader.loadClass("i00.c")
            val list = runCatching { manager.getMethod("p").invoke(null) }
                .getOrElse { manager.getDeclaredMethod("p").apply { isAccessible = true }.invoke(null) }
            val result = list.javaClass.getMethod("x").apply { isAccessible = true }.invoke(list)
            publishOwnedSnapshot(result)
        }.onFailure { XLog.w("wallet resume snapshot refresh failed", it) }
    }

    /** Snapshot the wallet's account-owned list; this is the sole home-membership source. */
    private fun installOnePlusOwnedSnapshotHook() {
        try {
            val cardClass = classLoader.loadClass("com.nearme.wallet.db.NfcCard")
            val manager = classLoader.loadClass("i00.c")
            val method = manager.getDeclaredMethod("x")
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    publishOwnedSnapshot(result)
                    return result
                }
            })
            installedCount++
            XLog.i("OnePlus owned-card snapshot hook installed: ${manager.name}#x")
        } catch (t: Throwable) {
            missingTargets.add("i00.c#x")
            XLog.w("owned-card snapshot hook unavailable", t)
        }
    }

    private fun publishOwnedSnapshot(result: Any?) {
        val source = result as? Iterable<*> ?: return
        val now = System.currentTimeMillis()
        if (!contextReady) return
        val previousAttempt = ownedSnapshotAt.get()
        if (now - previousAttempt < 800L || !ownedSnapshotAt.compareAndSet(previousAttempt, now)) return
        val cards = source.mapNotNull { item ->
            try {
                val type = item?.javaClass?.getMethod("getCardType")?.invoke(item)?.toString()
                val status = item?.javaClass?.getMethod("getCardStatus")?.invoke(item)?.toString()
                val aid = item?.javaClass?.getMethod("getAid")?.invoke(item)?.toString()
                val unique = item?.javaClass?.getMethod("getUniqueCardId")?.invoke(item)?.toString()
                val image = item?.javaClass?.getMethod("getCardImg")?.invoke(item)?.toString()
                    ?.takeIf { it.isNotBlank() }
                val name = com.dsh.miuix.cardface.core.CardNameResolver.fromCard(item)
                val candidate = com.dsh.miuix.cardface.core.OwnedCardPolicy.Candidate(type, status, aid, unique, image, name)
                if (com.dsh.miuix.cardface.core.OwnedCardPolicy.accept(candidate)) image!!.trim() to name else null
            } catch (_: Throwable) { null }
        }
        // i00.c#x() returns an empty list on database/account read errors. Keep the
        // last good membership snapshot until two consecutive empty reads confirm it.
        if (cards.isEmpty() && emptyOwnedSnapshotStreak.incrementAndGet() < 2) return
        if (cards.isNotEmpty()) emptyOwnedSnapshotStreak.set(0)
        val json = com.dsh.miuix.cardface.core.OwnedCardPolicy.snapshotJson(now, cards)
        settingsRepo.queueRuntime("owned_cards", "snapshot", json)
    }

    /** OnePlus/ColorOS 5.52.0: capture original card URLs and reuse the full editor/map pipeline. */
    private fun installOnePlusHooks() {
        installStableCardLoads()
        installKnownGlideLoaders()
        installOnePlusOwnedSnapshotHook()
        val targets = listOf(
            "com.nearme.wallet.db.NfcCard" to "getCardImg",
            "com.nearme.wallet.db.NfcCardDetail" to "getCardImg",
            "com.nearme.wallet.db.NfcCard" to "getNewCardImg",
            "com.nearme.common.domain.rsp.PayCardInfo" to "getCardImg",
            "com.nearme.pay.domain.rsp.CardDetailRspVo" to "getCardImg",
            "com.nearme.nfc.domain.transit.rsp.CardDetailRsp" to "getCardImg",
            "com.nearme.nfc.domain.transit.rsp.CardIndexDTO" to "getCardImg",
            "com.nearme.nfc.domain.door.rsp.SchoolCardDetailRsp" to "getCardImg",
        )
        for ((name, getter) in targets) {
            try {
                val method = classLoader.loadClass(name).getDeclaredMethod(getter)
                if (method.returnType != String::class.java) continue
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val original = chain.proceed()
                        try {
                            val url = original as? String
                            if (!url.isNullOrBlank()) observeOriginal(url, chain.thisObject)
                        } catch (t: Throwable) { XLog.w("card metadata capture failed", t) }
                        return original
                    }
                })
                installedCount++
                cardGetterCount++
            } catch (t: Throwable) {
                missingTargets.add("$name#$getter")
                XLog.w("optional OnePlus getter unavailable: $name#$getter", t)
            }
        }
        // Exact-URL mapping only: no global test replacement or unrelated image capture.
        try {
            val imageView = classLoader.loadClass("com.finshell.finui.widget.imageview.CircleNetworkImageView")
            for (method in imageView.declaredMethods.filter {
                it.name in setOf("g1", "i1", "x0", "y0") && it.parameterCount >= 1 && it.parameterTypes[0] == String::class.java
            }) {
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val rawUrl = chain.getArg(0) as? String ?: return chain.proceed()
                        if (rawUrl.startsWith(UrlMapper.LOCAL_IMAGE_URI_PREFIX)) return chain.proceed()
                        settingsRepo.snapshot()
                        val url = settingsRepo.originalForAsset(rawUrl) ?: rawUrl
                        val hasMapping = UrlMapper.lookupMapping(url, settingsRepo.mappings()) != null
                        if (!hasMapping && url == rawUrl) return chain.proceed()
                        val replacement = if (hasMapping) replaceUrl(url, false) else url
                        if (replacement.isEmpty()) return chain.proceed()
                        val args = Array<Any?>(method.parameterCount) { chain.getArg(it) }
                        args[0] = replacement
                        return chain.proceed(args)
                    }
                })
                installedCount++
                loaderCount++
            }
        } catch (t: Throwable) {
            missingTargets.add("CircleNetworkImageView.g1/i1")
            XLog.w("optional OnePlus image loader unavailable", t)
        }
    }

    private data class LoadedCard(val url: String, val modified: Long, val drawable: java.lang.ref.WeakReference<android.graphics.drawable.Drawable>)
    private val loadedCards = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<android.widget.ImageView, LoadedCard>())

    /** Keep successful local card content across the wallet's repeated initial data binds. */
    private fun installStableCardLoads() {
        runCatching {
            val image = classLoader.loadClass("com.finshell.finui.widget.imageview.CircleNetworkImageView")
            val getUrl = image.getMethod("getUrl")
            val target = classLoader.loadClass("com.finshell.finui.widget.imageview.CircleNetworkImageView\$d")
            val owner = target.getDeclaredField("c").apply { isAccessible = true }
            val requestUrls = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())
            target.declaredConstructors.forEach { constructor ->
                module.hook(constructor).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val view = owner.get(chain.thisObject)
                        (getUrl.invoke(view) as? String)?.let { requestUrls[chain.thisObject] = it }
                        return result
                    }
                })
            }
            val delivered = target.getDeclaredMethod("h", android.graphics.drawable.Drawable::class.java)
            module.hook(delivered).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val view = owner.get(chain.thisObject) as android.widget.ImageView
                    val url = requestUrls[chain.thisObject]
                    val currentUrl = getUrl.invoke(view) as? String
                    val drawable = chain.getArg(0) as? android.graphics.drawable.Drawable
                    if (url != null && url == currentUrl && url.startsWith("file://$walletImagesDir/") && drawable != null && view.drawable === drawable) {
                        loadedCards[view] = LoadedCard(url, File(android.net.Uri.parse(url).path.orEmpty()).lastModified(), java.lang.ref.WeakReference(drawable))
                    } else loadedCards.remove(view)
                    return result
                }
            })
            val placeholder = image.getDeclaredMethod("getPlaceHolderDrawable").apply { isAccessible = true }
            module.hook(placeholder).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val view = chain.thisObject as android.widget.ImageView
                    val loaded = loadedCards[view]
                    val url = getUrl.invoke(view) as? String
                    return if (loaded != null && loaded.url == url) loaded.drawable.get() ?: chain.proceed() else chain.proceed()
                }
            })
            val load = image.declaredMethods.single { it.name == "g1" && it.parameterCount == 4 }
            module.hook(load).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val raw = chain.getArg(0) as? String ?: return chain.proceed()
                    val view = chain.thisObject as android.widget.ImageView
                    val original = settingsRepo.originalForAsset(raw) ?: raw
                    val mapped = UrlMapper.lookupMapping(original, settingsRepo.mappings())
                    val effective = if (mapped != null) replaceUrl(original, false).ifBlank { raw } else raw
                    val loaded = loadedCards[view]
                    if (loaded != null && loaded.url == effective && view.drawable != null && view.drawable === loaded.drawable.get() &&
                        loaded.modified == File(android.net.Uri.parse(effective).path.orEmpty()).lastModified()) {
                        // Only skip callback-free rebinds. Keep callback delivery and retry semantics intact.
                        if (chain.getArg(3) == null) {
                            XLog.d("same card request retained")
                            return null
                        }
                    }
                    if (loaded != null && loaded.url != effective) loadedCards.remove(view)
                    val args = Array<Any?>(load.parameterCount) { chain.getArg(it) }
                    args[0] = effective
                    return chain.proceed(args)
                }
            })
            XLog.i("stable card placeholders installed")
        }.onFailure { XLog.w("stable card load hook unavailable", it) }
    }

    private fun installKnownGlideLoaders() {
        for ((type, methodName) in listOf("com.bumptech.glide.j" to "r", "com.bumptech.glide.RequestManager" to "load")) {
            try {
                val method = classLoader.loadClass(type).getDeclaredMethod(methodName, String::class.java)
                if (method.returnType.name !in setOf("com.bumptech.glide.i", "com.bumptech.glide.RequestBuilder")) continue
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val raw = chain.getArg(0) as? String ?: return chain.proceed()
                        if (raw.startsWith(UrlMapper.LOCAL_IMAGE_URI_PREFIX)) return chain.proceed()
                        settingsRepo.snapshot()
                        val original = settingsRepo.originalForAsset(raw) ?: raw
                        val mapped = UrlMapper.lookupMapping(original, settingsRepo.mappings())
                        if (mapped == null && original == raw) return chain.proceed()
                        val target = if (mapped == null) original else replaceUrl(original, false)
                        return if (target.isBlank()) chain.proceed() else chain.proceed(arrayOf<Any?>(target))
                    }
                })
                installedCount++; loaderCount++
            } catch (_: ClassNotFoundException) { }
            catch (_: NoSuchMethodException) { }
            catch (t: Throwable) { XLog.w("Glide display hook unavailable: $type#$methodName", t) }
        }
    }

    private fun observeOriginal(url: String, card: Any?) {
        // Membership comes only from i00.c#x snapshot. Metadata observation is retained for
        // mapping compatibility, but it never inserts a URL into card_urls or home membership.
        settingsRepo.snapshot()
        if (settingsRepo.isReplacementMaterial(url)) return
        settingsRepo.reportSource(card, walletPackage, url)
        settingsRepo.touchLastHookAt()
    }

    /**
     * 原版 startHook 的开关推导：
     *  - MIUI #0  ：从参数读 URL，直接返回替换值（load=true,  replaceArg=false）
     *  - MIUI #1  ：从参数读 URL，替换参数（load=true,  replaceArg=true）
     *  - MIUI #10 ：从返回值读 URL（load=false, replaceArg=false）
     */
    private fun flagsForSlot(slot: Int): Pair<Boolean, Boolean> = when {
        slot == MethodLocator.SLOT_URL_FROM_ARG -> true to false
        slot == MethodLocator.SLOT_REPLACE_ARG -> true to true
        slot == MethodLocator.SLOT_SUPER_ISLAND -> false to false
        else -> true to false
    }

    private fun install(
        method: Method,
        label: String,
        loadUrlFromArg: Boolean,
        replaceUrlToArg: Boolean,
        trackNewUrls: Boolean,
    ) {
        try {
            module.hook(method).intercept(UrlHooker(loadUrlFromArg, replaceUrlToArg, trackNewUrls, label == "oneplus:card-url"))
            installedCount++
            if (label == "oneplus:card-url") cardGetterCount++
            XLog.i(
                "hooked ${method.declaringClass.name}#${method.name} ($label, " +
                    "loadUrlFromArg=$loadUrlFromArg, replaceUrlToArg=$replaceUrlToArg)",
            )
        } catch (t: Throwable) {
            XLog.e("hook failed: ${method.declaringClass.name}#${method.name}", t)
        }
    }

    /** 与原版 Hook.MyHooker 完全一致的两种模式。 */
    private inner class UrlHooker(
        private val loadUrlFromArg: Boolean,
        private val replaceUrlToArg: Boolean,
        /** 是否把这个 URL 记入卡面列表；只有「卡面专用」hook 点才记录，通用 Glide 加载不记。 */
        private val trackNewUrls: Boolean,
        private val captureCardName: Boolean,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (loadUrlFromArg) {
                // 参数可能是 String / Uri / File（Glide 的 load(Object) 重载）。
                val raw = try {
                    chain.getArg(0)
                } catch (t: Throwable) {
                    null
                }
                val url = when (raw) {
                    is String -> raw
                    is android.net.Uri -> raw.toString()
                    is java.io.File -> raw.absolutePath
                    else -> null
                }
                val replaced = if (url == null) "" else replaceUrl(url, trackNewUrls)
                if (replaced.isEmpty()) return chain.proceed()
                if (!replaceUrlToArg) return replaced
                // 按原参数类型构造新参数，避免类型不匹配。
                val newArg: Any = when (raw) {
                    is android.net.Uri -> android.net.Uri.parse(replaced)
                    is java.io.File -> java.io.File(replaced.removePrefix("file://"))
                    else -> replaced
                }
                return chain.proceed(arrayOf<Any?>(newArg))
            }
            // 从返回值读 URL 模式：proceed 的异常保持原样抛出（模块只处理自己的异常）。
            val result = chain.proceed()
            val url = result as? String ?: return result
            if (captureCardName && url.isNotBlank()) {
                try {
                    if (!settingsRepo.isReplacementMaterial(url)) {
                        com.dsh.miuix.cardface.core.CardNameResolver.fromCard(chain.thisObject)?.let {
                            settingsRepo.reportCardName(url, it)
                        }
                    }
                } catch (t: Throwable) { XLog.w("card name capture failed", t) }
            }
            val replaced = replaceUrl(url, trackNewUrls)
            return if (replaced.isEmpty()) result else replaced
        }
    }

    // ---------- 核心替换 ----------

    fun replaceUrl(url: String, trackNew: Boolean = true): String {
        return try {
            val settings = settingsRepo.snapshot()
            val mappings = settingsRepo.mappings()
            if (settingsRepo.isReplacementMaterial(url)) return ""
            val onePlusDisplayPath = osType == MethodLocator.OS_ONEPLUS && !trackNew
            val env = UrlMapper.Env(
                isMiui = osType == MethodLocator.OS_MIUI,
                showAllCards = settings.showAllCards,
                testMode = settings.testMode,
                compatMode = settings.compatMode,
                blacklist = settingsRepo.blacklist(),
                mappings = mappings,
                walletImagesDir = walletImagesDir,
                existingWalletImages = if (onePlusDisplayPath) {
                    val name = UrlMapper.lookupMapping(url, mappings)
                    if (name != null && !name.contains('/') && File(walletImagesDir, name).let { it.isFile && it.length() > 0L }) setOf(name) else emptySet()
                } else walletImageNames(),
            )
            val result = UrlMapper.map(url, env)

            if (trackNew && result.trackUrl && !settingsRepo.isReplacementMaterial(url)) {
                settingsRepo.addCardUrl(url)
            }

            var finalUrl = result.url
            var copyError: String? = null
            val copy = result.copy
            if (copy != null) {
                if (copyToWallet(copy)) {
                    XLog.d("compat mode copied ${copy.localName} -> ${copy.destPath}")
                } else {
                    // root 不可用时退回 content://，保证卡面仍然能替换。
                    finalUrl = UrlMapper.LOCAL_IMAGE_URI_PREFIX + copy.localName
                    copyError = "本地图片同步失败，已回退 Provider；请重新同步或检查 Root 授权"
                    XLog.w("compat copy failed, fallback to content:// for ${copy.localName}")
                }
            }

            if (finalUrl.isNotEmpty() && !settings.testMode) {
                UrlMapper.lookupMapping(url, mappings)?.let { settingsRepo.reportApplication(url, it, copyError) }
            }
            settingsRepo.touchLastHookAt()
            XLog.i(
                "load image url: $url -> " +
                    (if (finalUrl.isEmpty()) "<keep original>" else finalUrl) +
                    " (track=${result.trackUrl})",
            )
            finalUrl
        } catch (t: Throwable) {
            XLog.e("replaceUrl error for $url", t)
            ""
        }
    }

    @Volatile
    private var walletImageCacheAt = 0L

    @Volatile
    private var walletImageCache: Set<String> = emptySet()

    /** 卡包 files/images 下已存在的文件名（2s 缓存，避免每次图片加载都 list 目录）。 */
    private fun walletImageNames(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - walletImageCacheAt < 2000L) return walletImageCache
        val names = try {
            File(walletImagesDir).listFiles()?.filter { com.dsh.miuix.cardface.core.ImageValidation.valid(it) }
                ?.map { it.name }?.toSet().orEmpty()
        } catch (_: Throwable) {
            emptySet()
        }
        walletImageCache = names
        walletImageCacheAt = now
        return names
    }

    /** 卡包加载我们自己复制进去的替换图时会产生 file:// URL；这种不是真实卡面，不记录。 */
    private fun isOwnReplacementUrl(url: String, replacementNames: Collection<String>): Boolean {
        val name = url.substringAfterLast('/')
        // 我们自己生成/复制的替换图：content:// 或 file:// 都不是真实卡面。
        if (replacementNames.contains(name)) return true
        if (name.matches(Regex("^card-\\d{8}-\\d{6}\\.png$"))) return true
        return url.contains("com.dsh.miuix.cardface")
    }

    private fun copyToWallet(copy: UrlMapper.CopyRequest): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < rootRetryAt) return false
        val applied = "$moduleDataDir/files/applied/${copy.localName}"
        val images = "$moduleDataDir/files/images/${copy.localName}"
        fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val first = com.dsh.miuix.cardface.core.AtomicFileCopy.script(applied, walletImagesDir, copy.localName)
        val second = com.dsh.miuix.cardface.core.AtomicFileCopy.script(images, walletImagesDir, copy.localName)
        // Source selection must run as Root, not under the wallet's sandbox identity.
        val result = RootShell.exec("if [ -f ${q(applied)} ]; then (\n$first\n); else (\n$second\n); fi", timeoutMs = 8000)
        val file = File(walletImagesDir, copy.localName)
        if (result.ok && com.dsh.miuix.cardface.core.ImageValidation.valid(file)) {
            walletImageCacheAt = 0L
            rootRetryAt = 0L
            return true
        }
        rootRetryAt = now + 30_000L
        if (System.currentTimeMillis() - lastCompatWarnMs > 60_000L) {
            lastCompatWarnMs = System.currentTimeMillis()
            XLog.w("local image sync not ready; provider fallback with bounded retry")
        }
        return false
    }
}
