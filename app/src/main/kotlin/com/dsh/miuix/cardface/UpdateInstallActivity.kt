package com.dsh.miuix.cardface

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dsh.miuix.cardface.core.VerifiedUpdateDownload
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Stages APK bytes before asking Android for installation confirmation; no private-cache URI handoff. */
class UpdateInstallActivity : Activity() {
    private lateinit var message: TextView
    @Volatile private var sessionId = -1
    @Volatile private var committed = false
    private val stateLock = Any()
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private var worker: java.util.concurrent.ExecutorService? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "安装更新"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        layout.addView(ProgressBar(this))
        message = TextView(this).apply {
            text = "正在把更新包交给系统…"
            textSize = 17f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        layout.addView(message)
        setContentView(layout)
        sessionId = state?.getInt("install_session", -1) ?: -1
        committed = state?.getBoolean("install_committed", false) ?: false
        if (sessionId >= 0) {
            active[sessionId] = WeakReference(this)
            if (committed) message.text = "正在等待系统安装结果…"
            else { abandon(); showError("更新准备被中断，请返回后重试") }
            return
        }
        val path = intent.getStringExtra(EXTRA_PATH)
        val source = path?.let(::File)
        val roots = listOf(File(filesDir, "updates"), cacheDir)
        if (source == null || !runCatching { roots.any { source.canonicalPath.startsWith(it.canonicalPath + File.separator) } }.getOrDefault(false)) {
            showError("更新文件路径无效")
            return
        }
        worker = Executors.newSingleThreadExecutor()
        worker!!.execute {
            var created = -1
            try {
                check(VerifiedUpdateDownload.verifyArchive(this, source, requireNewer = true)) { "更新包名、版本或签名校验失败" }
                check(!cancelled.get()) { "安装准备已取消" }
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(packageName)
                    setSize(source.length())
                    if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                created = installer.createSession(params)
                sessionId = created
                check(!cancelled.get()) { "安装准备已取消" }
                active[created] = WeakReference(this)
                installer.openSession(created).use { session ->
                    session.openWrite("base.apk", 0L, source.length()).use { output ->
                        source.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                check(!cancelled.get()) { "安装准备已取消" }
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        session.fsync(output)
                    }
                    val callback = Intent(this, com.dsh.miuix.cardface.core.UpdateInstallReceiver::class.java).apply {
                        action = "$packageName.UPDATE_SESSION_$created"
                        putExtra("expected_session", created)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val sender = PendingIntent.getBroadcast(this, created, callback, flags).intentSender
                    synchronized(stateLock) {
                        check(!cancelled.get()) { "安装准备已取消" }
                        session.commit(sender)
                        committed = true
                    }
                }
            } catch (t: Throwable) {
                committed = false
                if (created >= 0) { active.remove(created); runCatching { packageManager.packageInstaller.abandonSession(created) } }
                Handler(Looper.getMainLooper()).post { if (!isDestroyed) showError(t.message ?: "更新安装准备失败") }
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("install_session", sessionId)
        out.putBoolean("install_committed", committed)
        super.onSaveInstanceState(out)
    }

    private fun showError(text: String) {
        message.text = "$text\n\n按返回键退出后可重试。"
    }

    private fun abandon() {
        if (sessionId >= 0) runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
        active.remove(sessionId)
    }

    fun result(intent: Intent) {
        val expected = intent.getIntExtra("expected_session", -1)
        if (expected != sessionId) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) showError("系统安装确认页面未返回")
                else runCatching { startActivity(confirm); message.text = "请在系统页面确认安装" }
                    .onFailure { showError("打开系统安装确认页失败") }
            }
            PackageInstaller.STATUS_SUCCESS -> { active.remove(sessionId); finish() }
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                active.remove(sessionId)
                showError("安装未完成：$detail")
            }
        }
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            if (!committed) { cancelled.set(true); abandon() }
        }
        worker?.shutdownNow()
        if (active[sessionId]?.get() === this) active.remove(sessionId)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "checked_apk_path"
        private val active = java.util.concurrent.ConcurrentHashMap<Int, WeakReference<UpdateInstallActivity>>()
        fun deliver(context: Context, intent: Intent) {
            val session = intent.getIntExtra("expected_session", -1)
            val actual = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, session)
            if (session < 0 || session != actual || intent.action != "${context.packageName}.UPDATE_SESSION_$session") return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val target = active[session]?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
            if (target != null) { target.result(intent); return }
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else {
                val text = if (status == PackageInstaller.STATUS_SUCCESS) "更新安装完成" else "更新安装未完成，请重新打开应用重试"
                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
