package com.dsh.miuix.cardface.xposed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/** Wallet 5.52.0: use its menu model and holder, preserving original rows. */
class DetailCoverHook(private val module: XposedModule, private val classLoader: ClassLoader) {
    private val modulePackage = module.moduleApplicationInfo.packageName
    private val customType = 0x4346
    private val menuClass by lazy { classLoader.loadClass("com.nearme.wallet.bus.adapter.CardDetailMenuAdapter") }
    private val itemClass by lazy { classLoader.loadClass("com.nearme.wallet.bus.model.d") }

    fun install() {
        DoorCoverHook(module, classLoader).install()
        runCatching { installBus() }.onFailure { XLog.e("detail cover bus hook failed", it) }
        runCatching { installSchool() }.onFailure { XLog.w("detail cover school hook unavailable", it) }
        runCatching { installBank() }.onFailure { XLog.e("detail cover bank hook failed", it) }
    }

    private fun newItem(): Any = itemClass.getConstructor(
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    ).newInstance(0, 0, false, customType).also {
        itemClass.getMethod("k", String::class.java).invoke(it, "卡片封面")
    }

    private fun type(item: Any?): Int? = item?.let { itemClass.getMethod("g").invoke(it) as Int }

    private fun installBus() {
        // Names verified from classes7.dex smali, not JADX display aliases.
        val listInAdapter = menuClass.getDeclaredField("b").apply { isAccessible = true }
        val bind = menuClass.declaredMethods.single { it.name == "y" && it.parameterCount == 2 }
        module.hook(bind).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // Restore module-owned decorations before this holder is reused for another menu row.
                runCatching {
                    val holder = chain.getArg(0) ?: return@runCatching
                    val icon = holder.javaClass.getField("a").get(holder) as ImageView
                    if (icon.background is CoverIcon) icon.background = null
                    (holder.javaClass.getField("itemView").get(holder) as View).apply {
                        setOnClickListener(null)
                        contentDescription = null
                    }
                }
                val result = chain.proceed()
                runCatching {
                    val items = listInAdapter.get(chain.thisObject) as? List<*> ?: return@runCatching
                    if (type(items.getOrNull(chain.getArg(1) as Int)) == customType) {
                        bindClick(chain.getArg(0), chain.thisObject)
                        XLog.i("detail cover bus row bound")
                    }
                }.onFailure { XLog.w("detail cover bus bind failed", it) }
                return result
            }
        })
        // Add the row before the existing notification, avoiding a second full-list rebind.
        module.hook(menuClass.getMethod("notifyDataSetChanged")).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (menuClass.isInstance(chain.thisObject)) runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val items = listInAdapter.get(chain.thisObject) as? MutableList<Any>
                    if (items != null && items.none { type(it) == customType }) {
                        val transaction = items.indexOfFirst { type(it) == 2 }
                        items.add(if (transaction >= 0) transaction + 1 else 0, newItem())
                        XLog.i("detail cover bus menu inserted before notification")
                    }
                }.onFailure { XLog.w("detail cover bus menu insert failed", it) }
                return chain.proceed()
            }
        })
        XLog.i("detail cover bus v37 installed")
    }

    private fun installSchool() {
        val adapter = classLoader.loadClass("com.nearme.wallet.school.adapter.SchoolCardDetailMenuAdapter")
        val model = classLoader.loadClass("s60.b")
        val itemsField = adapter.getDeclaredField("b").apply { isAccessible = true }
        val kind = model.getMethod("c")
        val bind = adapter.declaredMethods.single { it.name == "y" && it.parameterCount == 2 }
        val detail = classLoader.loadClass("com.nearme.wallet.school.view.SchoolCardDetailActivity")
        val currentUrl = detail.getDeclaredField("h").apply { isAccessible = true }
        val activityItems = detail.getDeclaredField("n").apply { isAccessible = true }
        val activityAdapter = detail.getDeclaredField("o").apply { isAccessible = true }
        val notify = adapter.getMethod("notifyDataSetChanged")
        val constructor = model.getConstructor(Int::class.javaPrimitiveType, String::class.java)
        val owners = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Pair<java.lang.ref.WeakReference<Any>, Any>>())
        val attach = detail.getDeclaredMethod("setViews")
        module.hook(attach).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    val activity = chain.thisObject ?: return@runCatching
                    val instance = activityAdapter.get(activity) ?: return@runCatching
                    val original = activityItems.get(activity) as? MutableList<Any> ?: return@runCatching
                    val cover = original.firstOrNull { runCatching { kind.invoke(it) == customType }.getOrDefault(false) }
                        ?: constructor.newInstance(customType, "").also(original::add)
                    owners[instance] = java.lang.ref.WeakReference(activity) to cover
                    XLog.i("school detail cover row attached")
                }.onFailure { XLog.e("school detail cover attach failed", it) }
                return result
            }
        })
        module.hook(notify).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val adapterInstance = chain.thisObject
                val owner = if (adapterInstance == null) null else owners[adapterInstance]
                val activity = owner?.first?.get()
                if (activity != null) runCatching {
                    val source = activityItems.get(activity) as? MutableList<Any> ?: return@runCatching
                    val cover = owner.second
                    if (source.none { it === cover }) source.add(cover)
                }
                return chain.proceed()
            }
        })
        module.hook(bind).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                runCatching {
                    val holder = chain.getArg(0) ?: return@runCatching
                    val icon = holder.javaClass.getField("a").get(holder) as ImageView
                    if (icon.background is CoverIcon) icon.background = null
                    (holder.javaClass.getField("itemView").get(holder) as View).apply {
                        setOnClickListener(null)
                        contentDescription = null
                    }
                }
                val result = chain.proceed()
                runCatching {
                    val holder = chain.getArg(0) ?: return@runCatching
                    val instance = chain.thisObject ?: return@runCatching
                    val owner = owners[instance] ?: return@runCatching
                    val position = chain.getArg(1) as Int
                    val items = itemsField.get(instance) as? List<*> ?: return@runCatching
                    if (items.getOrNull(position) !== owner.second) return@runCatching
                    val title = holder.javaClass.getField("b").get(holder) as TextView
                    title.text = "卡片封面"
                    title.visibility = View.VISIBLE
                    val icon = holder.javaClass.getField("a").get(holder) as ImageView
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(null)
                    icon.background = CoverIcon(title.currentTextColor)
                    val row = holder.javaClass.getField("itemView").get(holder) as View
                    // Native y() installs SchoolCardDetailMenuAdapter$a capturing its position.
                    // Replace that listener on our row; invoking it would route the custom index
                    // into the wallet's original menu callback.
                    row.setOnClickListener {
                        val url = owner.first.get()?.let { currentUrl.get(it) as? String }
                        launchCoverEditor(row, url)
                    }
                }.onFailure { XLog.e("school detail cover bind failed", it) }
                return result
            }
        })
        XLog.i("school detail cover hooks installed")
    }

    private fun installBank() {
        val adapter = classLoader.loadClass("com.nearme.pay.business.carddetail.ui.NfcCardDetailRvAdapter")
        // Verify the known five-section contract before modifying item positions.
        val layouts = adapter.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == IntArray::class.java }
            .mapNotNull { runCatching { it.isAccessible = true; it.get(null) as? IntArray }.getOrNull() }
        require(layouts.any { it.size == 5 }) { "Bank detail layout contract changed; leave original adapter intact" }
        val count = adapter.getDeclaredMethod("getItemCount")
        val viewType = adapter.getDeclaredMethod("getItemViewType", Int::class.javaPrimitiveType)
        val create = adapter.declaredMethods.single { it.name == "onCreateViewHolder" && it.parameterCount == 2 }
        val bind = adapter.declaredMethods.single { it.name == "onBindViewHolder" && it.parameterCount == 2 }
        val menuCreate = menuClass.declaredMethods.single { it.name == "B" && it.parameterCount == 2 }
        val menuBind = menuClass.declaredMethods.single { it.name == "y" && it.parameterCount == 2 }
        // Insert before the original delete-button section (original index 4).
        module.hook(count).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? = (chain.proceed() as Int) + 1
        })
        module.hook(viewType).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val p = chain.getArg(0) as Int
                return if (p == 4) customType else chain.proceed(arrayOf(if (p > 4) p - 1 else p))
            }
        })
        module.hook(create).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (chain.getArg(1) as Int != customType) return chain.proceed()
                val parent = chain.getArg(0) as ViewGroup
                val menu = menuClass.getConstructor(Context::class.java, List::class.java)
                    .newInstance(parent.context, listOf(newItem()))
                val holder = menuCreate.invoke(menu, parent, 0)
                val root = holder.javaClass.getField("itemView").get(holder) as View
                val density = parent.resources.displayMetrics.density
                // The transit layout was inflated for a nested list. Give it explicit bounds
                // when it is used as a top-level bank RecyclerView item.
                root.layoutParams = root.layoutParams.apply {
                    height = (56 * density).toInt()
                    (this as? ViewGroup.MarginLayoutParams)?.topMargin = (12 * density).toInt()
                }
                root.setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                if (root is ViewGroup && root.childCount > 0) {
                    val child = root.getChildAt(0)
                    child.layoutParams = child.layoutParams.apply { height = (48 * density).toInt() }
                }
                return holder
            }
        })
        module.hook(bind).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val p = chain.getArg(1) as Int
                if (p != 4) return chain.proceed(arrayOf(chain.getArg(0), if (p > 4) p - 1 else p))
                val holder = chain.getArg(0) ?: return null
                val root = holder.javaClass.getField("itemView").get(holder) as View
                val menu = menuClass.getConstructor(Context::class.java, List::class.java)
                    .newInstance(root.context, listOf(newItem()))
                menuBind.invoke(menu, holder, 0)
                bindClick(holder, chain.thisObject)
                XLog.i("detail cover bank single row bound before delete")
                return null
            }
        })
        XLog.i("detail cover bank v37 installed")
    }

    private fun launchCoverEditor(view: View, url: String?) {
        runCatching {
            val valid = url?.takeIf { it.isNotBlank() } ?: return@runCatching XLog.w("detail cover current card image missing")
            val uri = android.net.Uri.parse(valid)
            if (uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                view.context.startActivity(Intent().apply {
                    setClassName(modulePackage, "com.dsh.miuix.cardface.MainActivity")
                    putExtra("cardface_route", "editor")
                    putExtra("cardface_url", valid)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                XLog.i("detail cover editor launched")
            } else XLog.w("detail cover current card image missing")
        }.onFailure { XLog.e("detail cover editor launch failed", it) }
    }

    private fun bindClick(holder: Any?, source: Any, urlOverride: (() -> String?)? = null) {
        if (holder == null) return
        val root = holder.javaClass.getField("itemView").get(holder) as View
        val click = holder.javaClass.getField("e").get(holder) as? View ?: root
        val icon = holder.javaClass.getField("a").get(holder) as ImageView
        val title = holder.javaClass.getField("b").get(holder) as TextView
        icon.visibility = View.VISIBLE
        icon.setImageDrawable(null)
        icon.background = CoverIcon(title.currentTextColor)
        val listener = View.OnClickListener { view ->
            runCatching {
                val url = urlOverride?.invoke()?.takeIf { it.isNotBlank() } ?: readModelUrl(source) ?: unwrapActivity(view.context)?.let { activity ->
                    readModelUrl(activity)
                        ?: activity.intent?.getStringExtra("cardImg")?.takeIf { it.isNotBlank() }
                }
                val uri = url?.let(android.net.Uri::parse)
                if (!url.isNullOrBlank() && uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
                    view.context.startActivity(Intent().apply {
                        setClassName(modulePackage, "com.dsh.miuix.cardface.MainActivity")
                        putExtra("cardface_route", "editor")
                        putExtra("cardface_url", url)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    })
                    XLog.i("detail cover editor launched")
                } else XLog.w("detail cover current card image missing")
            }.onFailure { XLog.e("detail cover editor launch failed", it) }
        }
        click.setOnClickListener(listener)
        root.setOnClickListener(listener)
        root.contentDescription = "卡片封面，打开卡面工坊编辑"
    }

    /** Resolution-independent card + landscape artwork, matching the wallet's 24dp icons. */
    internal class CoverIcon(private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@CoverIcon.color
            style = Paint.Style.STROKE
            strokeWidth = 1.65f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val mountain = Path().apply {
            moveTo(4f, 16f); lineTo(9f, 11f); lineTo(12f, 14f)
            lineTo(15f, 11f); lineTo(20f, 16f)
        }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            canvas.drawRoundRect(2.5f, 4.5f, 21.5f, 19.5f, 3f, 3f, paint)
            canvas.drawCircle(16.5f, 8.5f, 1.3f, paint)
            canvas.drawPath(mountain, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun readModelUrl(source: Any): String? = runCatching {
        when (source.javaClass.name) {
            "com.nearme.pay.business.carddetail.ui.NfcCardDetailRvAdapter" -> {
                val response = source.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(source)
                response?.javaClass?.getMethod("getCardImg")?.invoke(response) as? String
            }
            "com.nearme.wallet.nfc.ui.BusDetailActivity" -> {
                val response = source.javaClass.getDeclaredField("X").apply { isAccessible = true }.get(source)
                (response?.javaClass?.getMethod("getCardImg")?.invoke(response) as? String)?.takeIf { it.isNotBlank() }
                    ?: source.javaClass.getDeclaredField("R").apply { isAccessible = true }.get(source) as? String
            }
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun unwrapActivity(context: Context): Activity? {
        var current = context
        repeat(12) {
            if (current is Activity) return current as Activity
            val base = (current as? ContextWrapper)?.baseContext ?: return null
            if (base === current) return null
            current = base
        }
        return null
    }
}
