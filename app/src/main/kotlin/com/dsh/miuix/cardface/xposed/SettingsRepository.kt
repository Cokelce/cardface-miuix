package com.dsh.miuix.cardface.xposed

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.SystemClock
import io.github.libxposed.api.XposedModule

/**
 * 汇总设置来源：
 *  - libxposed `getRemotePreferences()`（LSPosed 数据库，被 hook 进程里**只读**、但会实时推送更新）
 *  - 模块 App 的 RemotePreferences provider（ContentResolver，可读写；App 的 SharedPreferences 真身）
 *
 * 两个来源做合并：provider 有值时覆盖 framework，保证 App 里刚改的设置尽快生效；
 * 如果 provider 因为包可见性等原因不可访问，则退化为只用 framework 数据。
 */
class SettingsRepository(private val module: XposedModule) {

    @Volatile
    private var context: Context? = null

    @Volatile
    private var provider: ProviderPrefs? = null

    @Volatile
    private var frameworkSettings: SharedPreferences? = null

    @Volatile
    private var frameworkCardUrls: SharedPreferences? = null

    @Volatile
    private var providerSettings: Map<String, Any?> = emptyMap()

    @Volatile
    private var providerCardUrls: Map<String, Any?> = emptyMap()

    @Volatile
    private var providerReadable = false

    /** 卡包 files/cardface.map 快照（App 写入），作为最低优先级兜底。 */
    @Volatile
    private var snapshotState = com.dsh.miuix.cardface.core.MappingPolicy.State(emptyMap(), 0)
    @Volatile private var currentProviderState: com.dsh.miuix.cardface.core.MappingPolicy.State? = null
    @Volatile private var cachedProviderState: com.dsh.miuix.cardface.core.MappingPolicy.State? = null
    @Volatile private var snapshotReadAt = 0L

    @Volatile
    private var dirty = true

    @Volatile private var lastProviderReadMs = 0L

    @Volatile
    private var lastHookAtWrittenMs = 0L

    private var observerRegistered = false

    private val pendingNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val pendingCardUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cardUrlFlushQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val savedNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val nameFlushQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val nameWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "cardface-names").apply { isDaemon = true }
    }

    fun reportCardName(url: String, rawName: String) {
        val name = com.dsh.miuix.cardface.core.CardNameResolver.clean(rawName) ?: return
        if (savedNames[url] == name) return
        if (pendingNames.size >= 512 && !pendingNames.containsKey(url)) return
        pendingNames[url] = name
        flushCardNames()
    }

    private fun flushCardNames() {
        if (provider == null || pendingNames.isEmpty() || !nameFlushQueued.compareAndSet(false, true)) return
        nameWorker.execute {
            var success = false
            try {
                val p = provider ?: return@execute
                val batch = pendingNames.toMap()
                val rows = batch.mapKeys { (key, _) -> key to com.dsh.miuix.cardface.core.HookPrefsProtocol.TYPE_STRING }
                if (p.bulkWrite("detected_names", rows)) {
                    success = true
                    batch.forEach { (url, name) ->
                        savedNames[url] = name
                        pendingNames.remove(url, name)
                    }
                }
            } catch (t: Throwable) { XLog.w("card name report failed", t) }
            finally {
                nameFlushQueued.set(false)
                if (success && pendingNames.isNotEmpty()) flushCardNames()
            }
        }
    }

    private val pendingRuntime = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, String>()
    private val reportedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val runtimeQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    fun reportApplication(url: String, replacement: String, error: String? = null) {
        val now = System.currentTimeMillis()
        val key = "apply:$url:$replacement:${error.orEmpty()}"
        synchronized(reportedAt) {
            if (now - (reportedAt[key] ?: 0L) < 15_000) return
            if (reportedAt.size > 1024) reportedAt.clear()
            reportedAt[key] = now
        }
        val value = org.json.JSONObject().put("at", now)
            .put("fingerprint", com.dsh.miuix.cardface.core.CardRuntime.fingerprint(replacement))
            .put("error", error.orEmpty()).toString()
        queueRuntime("apply_state", url, value)
    }

    fun queueRuntime(group: String, key: String, value: String) {
        if (pendingRuntime.size < 512 || pendingRuntime.containsKey(group to key)) pendingRuntime[group to key] = value
        flushRuntime()
    }

    private fun flushRuntime() {
        if (provider == null || pendingRuntime.isEmpty() || !runtimeQueued.compareAndSet(false, true)) return
        nameWorker.execute {
            var success = false
            try {
                val p = provider ?: return@execute
                pendingRuntime.toMap().entries.groupBy { it.key.first }.forEach { (group, entries) ->
                    val batch = entries.associate { (it.key.second to com.dsh.miuix.cardface.core.HookPrefsProtocol.TYPE_STRING) to it.value }
                    if (p.bulkWrite(group, batch)) {
                        success = true
                        entries.forEach { pendingRuntime.remove(it.key, it.value) }
                    }
                }
            } catch (t: Throwable) { XLog.w("runtime report failed", t) }
            finally {
                runtimeQueued.set(false)
                if (success && pendingRuntime.isNotEmpty()) flushRuntime()
            }
        }
    }

    @Volatile private var sourceGroups: List<Set<String>> = emptyList()
    @Volatile private var assetOrigins: Map<String, Set<String>> = emptyMap()
    @Volatile private var replacementAssetKeys: Set<String> = emptySet()
    @Volatile private var sourceReadAt = 0L
    private val observedSources = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private val refreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val refreshRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val refreshWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "cardface-settings").apply { isDaemon = true }
    }

    fun reportSource(card: Any?, wallet: String, url: String) {
        val id = com.dsh.miuix.cardface.core.CardSourceIndex.stableId(card, wallet) ?: return
        if (observedSources.size >= 256 && !observedSources.containsKey(id)) return
        val set = observedSources.getOrPut(id) { java.util.Collections.synchronizedSet(linkedSetOf()) }
        synchronized(set) {
            if (!set.add(url)) return
            nameWorker.execute {
                val p = provider ?: run { synchronized(set) { set.remove(url) }; return@execute }
                try {
                    val existing = p.readAll("card_sources")?.get(id) as? String
                    val old = existing?.let { org.json.JSONArray(it) }
                    val urls = linkedSetOf<String>()
                    if (old != null) for (i in 0 until old.length()) urls.add(old.getString(i))
                    synchronized(set) { urls.addAll(set) }
                    if (urls.size > 16) return@execute
                    if (p.putString("card_sources", id, org.json.JSONArray(urls.toList()).toString())) sourceReadAt = 0L
                    else synchronized(set) { set.remove(url) }
                } catch (_: Throwable) { synchronized(set) { set.remove(url) } }
            }
        }
    }

    private fun refreshSources() {
        val now = SystemClock.elapsedRealtime()
        if (now - sourceReadAt < 2000L) return
        requestRefresh()
    }

    private fun readSources(p: ProviderPrefs) {
        val now = SystemClock.elapsedRealtime()
        if (now - sourceReadAt < 2000L) return
        sourceReadAt = now
        fun strings(value: Any?): Set<String>? = runCatching {
            when(value) {
                is Set<*> -> value.filterIsInstance<String>().toSet()
                is String -> { val a = org.json.JSONArray(value); (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> emptySet()
            }
        }.getOrNull()
        p.readAll("card_sources")?.let { sourceGroups = it.values.mapNotNull { value -> strings(value) } }
        p.readAll("asset_origins")?.let { data -> assetOrigins = data.mapValues { strings(it.value).orEmpty() } }
        p.readAll("replacement_assets")?.let { replacementAssetKeys = it.keys }
    }

    fun isReplacementMaterial(url: String): Boolean =
        com.dsh.miuix.cardface.core.ReplacementAssets.isKnown(url, cachedMappings(), replacementAssetKeys, assetOrigins.keys)

    fun originalForAsset(url: String): String? {
        val key = com.dsh.miuix.cardface.core.CardRuntime.fingerprint(url)
        val nameKey = com.dsh.miuix.cardface.core.CardRuntime.fingerprint(url.substringAfterLast('/'))
        return com.dsh.miuix.cardface.core.CardSourceIndex.originalForAsset(url, cachedMappings(),
            assetOrigins[key].orEmpty() + assetOrigins[nameKey].orEmpty())
    }

    // ---------- 初始化 ----------

    fun init() {
        try {
            frameworkSettings = module.getRemotePreferences(GROUP_SETTINGS)
            frameworkCardUrls = module.getRemotePreferences(GROUP_CARD_URLS)
            XLog.i(
                "remote prefs ready: settings=${frameworkSettings?.all?.size ?: -1} keys, " +
                    "card_urls=${frameworkCardUrls?.all?.size ?: -1} keys",
            )
        } catch (t: Throwable) {
            // 非 LSPosed（embedded framework）/不支持 remote prefs 时这里是正常的
            XLog.w("remote prefs unavailable, fallback to provider only", t)
            frameworkSettings = null
            frameworkCardUrls = null
        }
        XLog.debug = rawBoolean(KEY_DEBUG, false)
    }

    fun attachContext(context: Context) {
        this.context = context.applicationContext ?: context
        // attachContext runs on cardface-context: publish local mappings before Provider IPC.
        loadSnapshot(force = true)
        provider = ProviderPrefs(this.context!!)
        requestRefresh(force = true, initialize = true)
        flushRuntime()
        flushCardNames()
        flushCardUrls()
        registerObserver()
    }

    /** App 侧设置变化时，ContentObserver 只清缓存，下次拦截时重读。 */
    private fun registerObserver() {
        val ctx = context ?: return
        if (observerRegistered) return
        try {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    requestRefresh(force = true)
                }
            }
            ctx.contentResolver.registerContentObserver(
                Uri.parse("content://${com.dsh.miuix.cardface.core.HookPrefsProtocol.AUTHORITY}/$GROUP_SETTINGS"),
                true,
                observer,
            )
            ctx.contentResolver.registerContentObserver(
                Uri.parse("content://${com.dsh.miuix.cardface.core.HookPrefsProtocol.AUTHORITY}/$GROUP_CARD_URLS"),
                true,
                observer,
            )
            observerRegistered = true
        } catch (t: Throwable) {
            XLog.w("register prefs observer failed", t)
        }
    }

    // ---------- 读 ----------

    fun refresh(force: Boolean = false) {
        flushCardUrls()
        if (force || dirty || SystemClock.elapsedRealtime() - lastProviderReadMs >= PROVIDER_REFRESH_INTERVAL_MS) {
            requestRefresh(force = force)
        }
    }

    private fun requestRefresh(force: Boolean = false, initialize: Boolean = false) {
        if (force) dirty = true
        refreshRequested.set(true)
        if (!refreshQueued.compareAndSet(false, true)) return
        val initializeNow = initialize
        refreshWorker.execute {
            var firstRead = initializeNow
            try {
                do {
                    refreshRequested.set(false)
                    val now = SystemClock.elapsedRealtime()
                    val shouldRead = firstRead || dirty || now - lastProviderReadMs >= PROVIDER_REFRESH_INTERVAL_MS
                    if (!shouldRead) continue
                    dirty = false
                    lastProviderReadMs = now
                    loadSnapshot(force = firstRead)
                    firstRead = false
                    val p = provider ?: continue
                    val settings = p.readAll(GROUP_SETTINGS)
                    currentProviderState = settings?.let { stateOf(it) }
                    currentProviderState?.let { cachedProviderState = it }
                    val cards = p.readAll(GROUP_CARD_URLS)
                    if (settings != null || cards != null) {
                        if (settings != null) { providerReadable = true; providerSettings = settings }
                        if (cards != null) providerCardUrls = cards
                    }
                    readSources(p)
                    XLog.i("hook context ready, provider prefs readable=$providerReadable")
                    flushCardUrls()
                } while (refreshRequested.get())
            } catch (t: Throwable) {
                XLog.w("settings refresh failed", t)
            } finally {
                refreshQueued.set(false)
                if (refreshRequested.get()) requestRefresh(force = dirty)
            }
        }
    }

    fun mergeSettings(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        frameworkSettings?.let { prefs ->
            try { out.putAll(prefs.all) } catch (t: Throwable) { XLog.w("read framework settings failed", t) }
        }
        // A successful provider read is the current app state, including removals.
        if (providerReadable) {
            out.clear()
            out.putAll(providerSettings)
        }
        return out
    }

    private fun loadSnapshot(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - snapshotReadAt < 1000) return
        snapshotReadAt = now
        val ctx = context ?: return
        try {
            val file = java.io.File(ctx.filesDir, "cardface.map")
            if (!file.isFile || file.length() > 2 * 1024 * 1024) return
            snapshotState = com.dsh.miuix.cardface.core.MappingSnapshot.decode(file.readText())
        } catch (t: Throwable) { XLog.w("snapshot read failed; retained last valid copy", t) }
    }

    private fun stateOf(data: Map<String, Any?>): com.dsh.miuix.cardface.core.MappingPolicy.State {
        val mappings = data.mapNotNull { (key, value) ->
            if ((key.startsWith("https://") || key.startsWith("http://")) && value is String && value.isNotBlank()) key to value else null
        }.toMap()
        return com.dsh.miuix.cardface.core.MappingPolicy.State(mappings, (data["mapping_revision"] as? Number)?.toLong() ?: 0L)
    }

    fun mergeCardUrls(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        frameworkCardUrls?.let { prefs ->
            try {
                out.putAll(prefs.all)
            } catch (t: Throwable) {
                XLog.w("read framework card_urls failed", t)
            }
        }
        out.putAll(providerCardUrls)
        return out
    }

    fun rawString(key: String): String? = mergeSettings()[key] as? String

    fun rawBoolean(key: String, default: Boolean): Boolean =
        when (val v = mergeSettings()[key]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true)
            else -> default
        }

    fun rawStringSet(key: String): Set<String> {
        val v = mergeCardUrls()[key] ?: mergeSettings()[key]
        return when (v) {
            is Set<*> -> v.filterIsInstance<String>().toSet()
            else -> emptySet()
        }
    }

    fun snapshot(): HookSettings {
        refresh()
        val s = mergeSettings()
        val compat = when {
            s.containsKey(KEY_COMPAT_COPY) -> s[KEY_COMPAT_COPY].asBoolean(false)
            s.containsKey(KEY_COMPAT_MODE) -> s[KEY_COMPAT_MODE].asBoolean(false)
            else -> false
        }
        val debug = s[KEY_DEBUG].asBoolean(false)
        XLog.debug = debug
        return HookSettings(
            debug = debug,
            showAllCards = s[KEY_SHOW_ALL_CARDS].asBoolean(false),
            superLand = s[KEY_SUPER_LAND].asBoolean(true),
            testMode = s[KEY_TEST_MODE].asBoolean(false),
            compatMode = compat,
            loadUrlFromArg = s[KEY_LOAD_FROM_ARG].asBoolean(false),
            replaceUrlToArg = s[KEY_REPLACE_TO_ARG].asBoolean(false),
            customClass = s[KEY_CUSTOM_CLASS].asString().orEmpty(),
            customMethod = s[KEY_CUSTOM_METHOD].asString().orEmpty(),
            customMethodArg = s[KEY_CUSTOM_METHOD_ARG].asString().orEmpty(),
        )
    }

    /** settings 里除保留键外的 String 项 = url -> 替换值（文件名 / http 地址）。 */
    private fun cachedMappings(): Map<String, String> {
        val framework = try { frameworkSettings?.all?.let { stateOf(it) } }
            catch (_: Throwable) { null }
        val base = com.dsh.miuix.cardface.core.MappingPolicy.select(currentProviderState, framework,
            snapshotState, cachedProviderState).mappings
        return com.dsh.miuix.cardface.core.CardSourceIndex.expand(base, sourceGroups)
    }

    fun mappings(): Map<String, String> {
        refresh()
        refreshSources()
        return cachedMappings()
    }

    fun blacklist(): Set<String> = rawStringSet(KEY_BLACK_CARD_URLS)

    fun cardUrls(): Set<String> = rawStringSet(KEY_ALL_CARD_URLS) + pendingCardUrls

    // ---------- 写 ----------

    /** Queue the throttled status write so drawable/network callbacks never block on Provider IPC. */
    fun touchLastHookAt(force: Boolean = false) {
        val wall = System.currentTimeMillis()
        synchronized(this) {
            if (!force && wall - lastHookAtWrittenMs < LAST_HOOK_AT_INTERVAL_MS) return
            lastHookAtWrittenMs = wall
        }
        nameWorker.execute {
            val p = provider
            val ok = p?.putLong(GROUP_SETTINGS, KEY_LAST_HOOK_AT, wall) == true
            if (ok) {
                providerSettings = LinkedHashMap(providerSettings).apply { put(KEY_LAST_HOOK_AT, wall) }
                XLog.d("last_hook_at updated")
            } else {
                synchronized(this) { if (lastHookAtWrittenMs == wall) lastHookAtWrittenMs = 0L }
                XLog.w("write last_hook_at failed (provider unavailable or not writable)")
            }
        }
    }

    /** 把新 URL 加入 card_urls/all_card_url_set；已存在返回 false。 */
    @Synchronized
    fun addCardUrl(url: String): Boolean {
        if (url.isEmpty() || isReplacementMaterial(url)) return false
        val current = cardUrls()
        if (url in current || pendingCardUrls.contains(url)) return false
        if (pendingCardUrls.size >= MAX_PENDING_CARD_URLS) return false
        pendingCardUrls.add(url)
        val next = LinkedHashSet(current).apply { add(url) }
        providerCardUrls = LinkedHashMap(providerCardUrls).apply { put(KEY_ALL_CARD_URLS, next) }
        flushCardUrls()
        return true
    }

    private fun flushCardUrls() {
        if (pendingCardUrls.isEmpty() || provider == null || !cardUrlFlushQueued.compareAndSet(false, true)) return
        nameWorker.execute {
            var success = false
            try {
                val p = provider ?: return@execute
                val batch = pendingCardUrls.toSet()
                if (batch.isNotEmpty() && p.unionStringSet(GROUP_CARD_URLS, KEY_ALL_CARD_URLS, batch)) {
                    batch.forEach { pendingCardUrls.remove(it) }
                    sourceReadAt = 0L
                    success = true
                }
            } catch (t: Throwable) {
                XLog.w("card url union failed; retaining pending urls", t)
            } finally {
                cardUrlFlushQueued.set(false)
                if (success && pendingCardUrls.isNotEmpty()) flushCardUrls()
            }
        }
    }

    // ---------- 工具 ----------

    private fun Any?.asBoolean(default: Boolean): Boolean = when (this) {
        null -> default
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> equals("true", ignoreCase = true)
        else -> default
    }

    private fun Any?.asString(): String? = when (this) {
        null -> null
        is String -> this
        else -> toString()
    }

    companion object {
        const val GROUP_SETTINGS = "settings"
        const val GROUP_CARD_URLS = "card_urls"

        const val KEY_HIDE_ICON = "hide_icon"
        const val KEY_SHOW_ALL_CARDS = "show_all_cards"
        const val KEY_SUPER_LAND = "super_land"
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_DEBUG = "debug"
        const val KEY_DEV_MODE = "dev_mode_open"
        const val KEY_FIRST_RUN = "first_run"
        const val KEY_CUSTOM_CLASS = "class"
        const val KEY_CUSTOM_METHOD = "method"
        const val KEY_CUSTOM_METHOD_ARG = "method_arg"
        const val KEY_LOAD_FROM_ARG = "load_url_from_arg"
        const val KEY_REPLACE_TO_ARG = "replace_url_to_arg"
        const val KEY_LAST_HOOK_AT = "last_hook_at"
        const val KEY_COMPAT_COPY = "compat_copy"
        const val KEY_COMPAT_MODE = "compat_mode"
        const val KEY_ALL_CARD_URLS = "all_card_url_set"
        const val KEY_BLACK_CARD_URLS = "black_card_url_set"

        /** 这些键不是 url -> 替换值 的映射。 */
        val RESERVED_KEYS = setOf(
            KEY_HIDE_ICON, KEY_SHOW_ALL_CARDS, KEY_SUPER_LAND, KEY_TEST_MODE, KEY_DEBUG,
            KEY_DEV_MODE, KEY_FIRST_RUN, KEY_CUSTOM_CLASS, KEY_CUSTOM_METHOD,
            KEY_CUSTOM_METHOD_ARG, KEY_LOAD_FROM_ARG, KEY_REPLACE_TO_ARG, KEY_LAST_HOOK_AT,
            KEY_COMPAT_COPY, KEY_COMPAT_MODE, "card_style", "card_style_mode",
        )

        private const val PROVIDER_REFRESH_INTERVAL_MS = 1500L
        private const val LAST_HOOK_AT_INTERVAL_MS = 2000L
        private const val MAX_PENDING_CARD_URLS = 512
    }
}

/** 一次 replaceUrl 需要的设置快照。 */
data class HookSettings(
    val debug: Boolean = false,
    val showAllCards: Boolean = false,
    val superLand: Boolean = false,
    val testMode: Boolean = false,
    val compatMode: Boolean = false,
    val loadUrlFromArg: Boolean = false,
    val replaceUrlToArg: Boolean = false,
    val customClass: String = "",
    val customMethod: String = "",
    val customMethodArg: String = "",
)
