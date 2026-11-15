package com.dsh.miuix.cardface.xposed

import android.content.Intent
import android.net.Uri
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/** Wallet 5.52.0 contracts checked against classes3/classes6 smali, not JADX aliases. */
class DoorCoverHook(private val module: XposedModule, private val classLoader: ClassLoader) {
    private class Owner(val fragment: WeakReference<Any>, val url: Field, val item: Any)

    private val owners = Collections.synchronizedMap(WeakHashMap<Any, Owner>())
    // Values deliberately do not retain the key (or a Fragment/adapter).
    private val customItems = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val modulePackage = module.moduleApplicationInfo.packageName

    fun install() {
        runCatching { installVerifiedHooks() }
            .onFailure { XLog.e("door cover hook installation failed", it) }
    }

    private fun installVerifiedHooks() {
        val adapter = classLoader.loadClass("com.nearme.wallet.entrance.adapter.nfcDetailAdapter.NfcDetailAdapter")
        val base = classLoader.loadClass("com.nearme.wallet.base.BaseMultipleAdapter")
        val model = classLoader.loadClass("f30.c")
        val bean = classLoader.loadClass("f30.a")
        val holder = classLoader.loadClass("com.nearme.wallet.entrance.adapter.nfcDetailAdapter.viewHolder.NfcDetailNormalMenuViewHolder")
        val callback = classLoader.loadClass("com.nearme.wallet.entrance.adapter.nfcDetailAdapter.NfcDetailAdapter\$c")
        val intType = Integer.TYPE
        val constructor = model.getConstructor(
            intType, String::class.java, String::class.java, String::class.java,
            String::class.java, java.lang.Boolean.TYPE, intType, String::class.java,
            String::class.java, String::class.java, intType, intType,
        )
        val itemsField = base.getDeclaredField("a").apply { isAccessible = true }
        val rootField = holder.getDeclaredField("a").apply { isAccessible = true }
        val iconField = holder.getDeclaredField("b").apply { isAccessible = true }
        val titleField = holder.getDeclaredField("c").apply { isAccessible = true }
        val callbackAdapter = callback.getDeclaredField("a").apply { isAccessible = true }
        val bind = holder.getDeclaredMethod("Y", intType, bean, intType)
        val originalType = adapter.getDeclaredMethod("y", intType)
        val click = callback.getDeclaredMethod("a", intType, model)
        val setData = adapter.getDeclaredMethod("setData", List::class.java)
        val targets = listOf(
            listOf("com.nearme.wallet.entrance.fragment.DoorDetailFragment", "setViews", "F", "r"),
            listOf("com.finshell.wallet.odoor.ODoorDetailFragment", "F0", "p", "t"),
        ).mapNotNull { (className, setupName, adapterName, urlName) ->
            runCatching {
                val fragment = classLoader.loadClass(className)
                val setup = fragment.getDeclaredMethod(setupName)
                val adapterField = fragment.getDeclaredField(adapterName)
                val urlField = fragment.getDeclaredField(urlName)
                require(adapterField.type == adapter && urlField.type == String::class.java)
                adapterField.isAccessible = true
                urlField.isAccessible = true
                Triple(setup, adapterField, urlField)
            }.onFailure { XLog.w("door cover target unavailable: $className", it) }.getOrNull()
        }
        require(targets.isNotEmpty()) { "No verified door fragment targets found" }
        require(itemsField.type == List::class.java && rootField.type == View::class.java)
        require(iconField.type == classLoader.loadClass("com.finshell.finui.widget.imageview.CircleNetworkImageView"))
        require(titleField.type == android.widget.TextView::class.java)
        require(callbackAdapter.type == adapter)

        // The adapter creates the normal holder for unhandled types; the model's own kind selects that path.
        val customType = 0x434644

        module.hook(setData).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val owner = owners[chain.thisObject] ?: return chain.proceed()
                val source = chain.getArg(0) as? List<*> ?: return chain.proceed()
                // Copy; never add to Fragment.H/q, which original click handlers index directly.
                val copy = ArrayList<Any>(source.size + 1)
                source.forEach { item -> if (item != null && item !== owner.item) copy.add(item) }
                copy.add(owner.item) // Append only: every original position and model stays intact.
                return chain.proceed(arrayOf(copy))
            }
        })

        module.hook(originalType).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val adapterInstance = chain.thisObject ?: return chain.proceed()
                val position = chain.getArg(0) as Int
                val item = (itemsField.get(adapterInstance) as? List<*>)?.getOrNull(position)
                return if (item != null && customItems.containsKey(item)) customType else chain.proceed()
            }
        })
        module.hook(bind).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed() // Native styling and native per-bind listener reset.
                val item = chain.getArg(1) ?: return result
                if (!customItems.containsKey(item)) return result
                runCatching {
                    val boundHolder = chain.thisObject ?: return@runCatching
                    val holderClass = boundHolder.javaClass
                    val root = rootField.get(boundHolder) as View
                    val icon = iconField.get(boundHolder) as android.widget.ImageView
                    val title = titleField.get(boundHolder) as android.widget.TextView
                    icon.apply {
                        setImageDrawable(null)
                        background = DetailCoverHook.CoverIcon(title.currentTextColor)
                    }
                    val owner = synchronized(owners) { owners.values.firstOrNull { it.item === item } }
                    val row = holderClass.getField("itemView").get(boundHolder) as View
                    val listener = View.OnClickListener { if (owner != null) launchEditor(row, owner) }
                    root.setOnClickListener(listener)
                    row.setOnClickListener(listener)
                }.onFailure { XLog.e("door cover row bind failed", it) }
                return result
            }
        })

        // The native holder callback forwards only position to Fragment.H/q. Consume our bean
        // even if custom binding failed, so the appended index never reaches a wallet handler.
        module.hook(click).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val item = chain.getArg(1)
                return if (item != null && customItems.containsKey(item)) null else chain.proceed()
            }
        })

        targets.forEach { (setup, adapterField, urlField) ->
            module.hook(setup).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    runCatching {
                        val fragment = chain.thisObject ?: return@runCatching
                        val instance = adapterField.get(fragment) ?: return@runCatching
                        if (owners.containsKey(instance)) return@runCatching
                        // f30.c: icon,title,content,color,extra,flag,viewType,dayIcon,
                        // nightIcon,extra,menuId,cardListStyle. Style 4 = dc.a.f() (single).
                        val item = constructor.newInstance(
                            android.R.drawable.ic_menu_gallery, "卡片封面", "", "", "", false,
                            customType, "", "", "", 0, 4,
                        )
                        val source = itemsField.get(instance) as? List<*> ?: return@runCatching
                        customItems[item] = true
                        owners[instance] = Owner(WeakReference(fragment), urlField, item)
                        try {
                            // Original setData copies and notifies; our hook adds the row first.
                            setData.invoke(instance, source)
                        } catch (failure: Throwable) {
                            owners.remove(instance)
                            customItems.remove(item)
                            throw failure
                        }
                        XLog.i("door cover native row attached: ${setup.declaringClass.simpleName}")
                    }.onFailure { XLog.e("door cover attach failed", it) }
                    return result
                }
            })
        }
        XLog.i("door cover native adapter hooks installed")
    }

    private fun launchEditor(view: View, owner: Owner) {
        runCatching {
            val fragment = owner.fragment.get() ?: return@runCatching
            // Read at click time: Door.r is updated by p1 and cover-change events;
            // ODoor.t is the current cardImg copied in preInit and used by its style action.
            val url = (owner.url.get(fragment) as? String)?.takeIf { it.isNotBlank() }
                ?: return@runCatching XLog.w("door cover current URL missing")
            val uri = Uri.parse(url)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                XLog.w("door cover current URL is not an editor image URL")
                return@runCatching
            }
            view.context.startActivity(Intent().apply {
                setClassName(modulePackage, "com.dsh.miuix.cardface.MainActivity")
                putExtra("cardface_route", "editor")
                putExtra("cardface_url", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        }.onFailure { XLog.e("door cover editor launch failed", it) }
    }
}
