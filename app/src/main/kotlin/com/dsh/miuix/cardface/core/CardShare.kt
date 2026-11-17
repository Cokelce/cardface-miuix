package com.dsh.miuix.cardface.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** 通过系统分享面板发送卡面图片 / 诊断信息。 */
object CardShare {

    private fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun shareImage(context: Context, file: File) {
        if (!file.isFile) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享卡面").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareTextFile(context: Context, file: File, title: String = "分享文件") {
        if (!file.isFile) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
