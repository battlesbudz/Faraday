package com.battlesbudz.jarvis.v2.code

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * The install hand-off for exported project APKs (Decision B, taken by
 * Battles 2026-11-05: Faraday carries `REQUEST_INSTALL_PACKAGES` and
 * export finishes with an Install action in the app instead of
 * save-and-install-from-Files). This unit builds the surface; the Code
 * screen's Install button that calls it is Unit 6.
 *
 * The exported file is shared with the system package installer
 * through the app's `code-exports` FileProvider (cache dir), with a
 * read grant scoped to that one file. When the user has not yet
 * allowed Faraday to install unknown apps, [unknownAppSourcesIntent]
 * is the settings screen the UI sends him to first; the permission is
 * per-app and Android owns that toggle, not Faraday.
 */
object CodeApkInstaller {

    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /** True when Android currently lets Faraday start package installs. */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The intent that hands [apkFile] to the system package installer. */
    fun installIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.code-exports", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Android's per-app "install unknown apps" settings screen for Faraday. */
    fun unknownAppSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
