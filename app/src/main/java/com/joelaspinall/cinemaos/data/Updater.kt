package com.joelaspinall.cinemaos.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Downloads the newest build from GitHub and hands it to Android's installer. */
class Updater(private val context: Context, private val store: Store) {

    suspend fun downloadAndInstall(onProgress: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            onProgress("Allow Cinema OS to install updates, then press Update again.")
            val i = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
            return
        }
        onProgress("Downloading the latest version…")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "cinema-os.apk")
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(store.settings.value.updateUrl).build()
            Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("Download failed (HTTP ${res.code}). Is the GitHub repo public?")
                res.body!!.byteStream().use { input -> apk.outputStream().use { input.copyTo(it) } }
            }
        }
        onProgress("Installing…")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(install)
    }
}
