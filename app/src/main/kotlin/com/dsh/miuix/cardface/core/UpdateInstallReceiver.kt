package com.dsh.miuix.cardface.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit receiver used only by this application's PackageInstaller session PendingIntent. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        com.dsh.miuix.cardface.UpdateInstallActivity.deliver(context, intent)
    }
}
