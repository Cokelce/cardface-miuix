package com.dsh.miuix.cardface.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/** System-session installer; never hands a private cache content URI to the external installer. */
object UpdateInstaller {
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun install(context: Context, apk: File): Boolean = runCatching {
        require(apk.isFile && UpdateArtifactPolicy.validateSize(apk.length(), 0) && UpdateArtifactPolicy.validateZip(apk))
        context.startActivity(Intent(context, com.dsh.miuix.cardface.UpdateInstallActivity::class.java).apply {
            putExtra(com.dsh.miuix.cardface.UpdateInstallActivity.EXTRA_PATH, apk.canonicalPath)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    }.onFailure { android.util.Log.w("CardFace.Update", "Installation handoff failed", it) }.getOrDefault(false)
}
