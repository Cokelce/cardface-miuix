package com.dsh.miuix.cardface.xposed

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/** Injects a non-payment cover row into every OnePlus wallet detail screen. */
class DetailCoverHook(private val module: XposedModule, private val classLoader: ClassLoader) {
    private val modulePackage = module.moduleApplicationInfo.packageName
    private val handled = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())

    fun install() {
        XLog.i("detail cover hook install begin")
        listOf(
            "com.nearme.wallet.nfc.ui.BusDetailActivity",
            "com.nearme.pay.business.carddetail.ui.BankCardDetailActivity",
        ).forEach { activityName ->
            runCatching {
                val clazz = classLoader.loadClass(activityName)
                val resume = clazz.getDeclaredMethod("onResume")
                XLog.i("detail cover target hooked: $activityName")
                module.hook(resume).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val activity = chain.thisObject as? Activity
                        if (activity != null) {
                            activity.window.decorView.postDelayed({ injectWithRetry(activity, 0) }, 350L)
                        }
                        return result
                    }
                })
            }.onFailure { XLog.w("detail cover hook unavailable: $activityName", it) }
        }
    }

    private fun injectWithRetry(activity: Activity, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        inject(activity)
        if (!rootHasCover(activity.window.decorView) && attempt < 3) {
            activity.window.decorView.postDelayed({ injectWithRetry(activity, attempt + 1) }, 650L)
        }
    }

    private fun inject(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (rootHasCover(activity.window.decorView)) return
        if (!handled.add(activity)) return
        val root = activity.window.decorView as? ViewGroup ?: return
        val cardUrl = findCardImage(activity) ?: run { handled.remove(activity); return }
        val row = makeRow(activity, cardUrl)
        val target = findInsertionParent(root)
        if (target == null) {
            XLog.w("detail cover insertion parent not found: ${activity.javaClass.name}")
            handled.remove(activity)
            return
        }
        val index = findAnchorIndex(target)
        val params = if (index in 0 until target.childCount) {
            target.getChildAt(index).layoutParams?.let { ViewGroup.LayoutParams(it) }
        } else ViewGroup.LayoutParams(-1, -2)
        target.addView(row, (index + 1).coerceAtMost(target.childCount), params)
        XLog.i("detail cover row injected: ${activity.javaClass.name}")
    }

    private fun rootHasCover(view: View): Boolean = when {
        view.tag == "cardface-detail-cover" -> true
        view is ViewGroup -> (0 until view.childCount).any { rootHasCover(view.getChildAt(it)) }
        else -> false
    }

    private fun findCardImage(activity: Activity): String? {
        val loader = classLoader
        // Current BusDetailActivity stores the detail object in field X and exposes getCardImg().
        var current: Class<*>? = activity.javaClass
        while (current != null) {
            current.declaredFields.firstOrNull { field ->
                runCatching { field.type.getMethod("getCardImg") }.isSuccess
            }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(activity)?.let { field.type.getMethod("getCardImg").invoke(it) as? String }
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            current = current.superclass
        }
        return null
    }

    private fun findInsertionParent(root: ViewGroup): ViewGroup? {
        var found: ViewGroup? = null
        fun visit(view: View) {
            if (found != null) return
            if (view is ViewGroup) {
                val entry = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
                val text = collectText(view)
                if (entry == "rootLayout" && (text.contains("交易记录") || text.contains("迁移卡片") || text.contains("卡片信息"))) {
                    found = view.parent as? ViewGroup
                    return
                }
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(root)
        if (found != null) return found
        // Fallback: locate the service label, then use its rootLayout parent—not the row itself.
        fun findLabel(view: View): View? {
            if (view is TextView && listOf("交易记录", "迁移卡片", "卡片信息").any { view.text?.toString()?.contains(it) == true }) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findLabel(view.getChildAt(i))?.let { return it }
            return null
        }
        val label = findLabel(root) ?: return null
        val row = label.parent as? ViewGroup ?: return null
        return row.parent as? ViewGroup
    }

    private fun findAnchorIndex(parent: ViewGroup): Int {
        for (i in 0 until parent.childCount) {
            val text = collectText(parent.getChildAt(i))
            if (text.contains("交易记录") || text.contains("迁移卡片")) return i
        }
        return parent.childCount - 1
    }
    private fun collectText(view: View): String = when (view) {
        is TextView -> view.text?.toString().orEmpty()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { collectText(view.getChildAt(it)) }
        else -> ""
    }

    private fun makeRow(context: Context, url: String): View {
        val row = LinearLayout(context).apply {
            tag = "cardface-detail-cover"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 24, 40, 24)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            contentDescription = "卡片封面"
        }
        val title = TextView(context).apply {
            text = "卡片封面"
            textSize = 16f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val summary = TextView(context).apply {
            text = "打开卡面工坊编辑"
            textSize = 13f
            setTextColor(0xFF9E9E9E.toInt())
            gravity = Gravity.END
        }
        row.addView(title); row.addView(summary)
        row.setOnClickListener {
            val intent = Intent().apply {
                setClassName(modulePackage, "$modulePackage.MainActivity")
                putExtra("cardface_route", "editor")
                putExtra("cardface_url", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        return row
    }
}
