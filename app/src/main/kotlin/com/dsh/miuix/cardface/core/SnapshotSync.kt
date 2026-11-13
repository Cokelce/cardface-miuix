package com.dsh.miuix.cardface.core

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Serialize app-side snapshot writes; always serialize the latest committed map, including clears. */
object SnapshotSync {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "cardface-snapshot").apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    fun request(context: Context) {
        dirty.set(true)
        if (!queued.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker.execute {
            try {
                while (dirty.getAndSet(false)) {
                    val ok = CardSync.writeMapSnapshot(app, AppPrefs(app))
                    if (!ok) android.util.Log.w("CardFaceSync", "snapshot sync pending; retry on next edit or startup")
                }
            } finally {
                queued.set(false)
                if (dirty.get()) request(app)
            }
        }
    }
}
