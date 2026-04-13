package com.mycut.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageStore {
    fun saveBitmap(context: Context, bitmap: Bitmap, prefix: String): Uri {
        val dir = File(context.cacheDir, "images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "${prefix}_${UUID.randomUUID()}.png")
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun copyImageUriToClipboard(context: Context, uri: Uri) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newUri(context.contentResolver, "MyCut Image", uri)
        clipboard.setPrimaryClip(clip)
    }
}
