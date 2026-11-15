package com.dsh.miuix.cardface

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.dsh.miuix.cardface.ui.AppModel
import com.dsh.miuix.cardface.ui.EditorDraftState

class MainActivity : ComponentActivity() {
    data class ExternalEditorRequest(val url: String, val revision: Long)

    var externalEditorRequest by mutableStateOf<ExternalEditorRequest?>(null)
        private set
    var activeModel: AppModel? = null
    var restoredDraftState: Bundle? = null
        private set
    fun takeRestoredDraft(): Bundle? = restoredDraftState.also { restoredDraftState = null }
    fun consumeExternalRequest(revision: Long) {
        if (externalEditorRequest?.revision == revision) {
            intent?.removeExtra("cardface_route")
            intent?.removeExtra("cardface_url")
            externalEditorRequest = null
        }
    }

    private var lastRequestUrl: String? = null
    private var requestRevision = 0L

    private fun consumeEditorIntent(intent: android.content.Intent?, allowSameUrl: Boolean) {
        if (intent?.getStringExtra("cardface_route") != "editor") return
        val rawUrl = intent.getStringExtra("cardface_url") ?: return
        val uri = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull() ?: return
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return
        if (!allowSameUrl && rawUrl == lastRequestUrl) return
        lastRequestUrl = rawUrl
        externalEditorRequest = ExternalEditorRequest(rawUrl, ++requestRevision)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeEditorIntent(intent, allowSameUrl = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredDraftState = savedInstanceState
        lastRequestUrl = savedInstanceState?.getString(KEY_LAST_REQUEST_URL)
        requestRevision = savedInstanceState?.getLong(KEY_REQUEST_REVISION) ?: 0L
        if (savedInstanceState != null) {
            externalEditorRequest = savedInstanceState.getString(KEY_PENDING_URL)?.let { url ->
                val revision = savedInstanceState.getLong(KEY_PENDING_REVISION)
                if (revision > 0L) ExternalEditorRequest(url, revision) else null
            }
        } else {
            consumeEditorIntent(intent, allowSameUrl = true)
        }
        enableEdgeToEdge()
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
            if (prefs.hideIcon) {
                com.dsh.miuix.cardface.core.PackageOps.postHiddenEntryNotification(this)
            }
        } catch (_: Throwable) {
        }
        setContent { CardFaceApp() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val request = externalEditorRequest
        outState.putString(KEY_LAST_REQUEST_URL, lastRequestUrl)
        outState.putLong(KEY_REQUEST_REVISION, requestRevision)
        outState.putString(KEY_PENDING_URL, request?.url)
        outState.putLong(KEY_PENDING_REVISION, request?.revision ?: 0L)
        activeModel?.let { EditorDraftState.saveScalars(outState, it) }
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_LAST_REQUEST_URL = "cardface.last_editor_url"
        private const val KEY_REQUEST_REVISION = "cardface.editor_revision"
        private const val KEY_PENDING_URL = "cardface.pending_editor_url"
        private const val KEY_PENDING_REVISION = "cardface.pending_editor_revision"
    }
}
