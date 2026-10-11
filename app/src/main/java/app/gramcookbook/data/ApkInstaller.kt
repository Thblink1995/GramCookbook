package app.gramcookbook.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** Hands a downloaded APK to Android's package installer. */
object ApkInstaller {
    enum class Signature { SAME, DIFFERENT, UNKNOWN }

    /**
     * Whether [apk] is signed like the installed app. Android refuses an update signed with
     * another key, with an unhelpful message, so the app says it first.
     */
    fun compareSignature(context: Context, apk: File): Signature {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk.path) ?: return Signature.UNKNOWN
        val installed = installedInfo(pm, context.packageName) ?: return Signature.UNKNOWN
        val a = certificates(archive)
        val b = certificates(installed)
        if (a.isEmpty() || b.isEmpty()) return Signature.UNKNOWN
        return if (a == b) Signature.SAME else Signature.DIFFERENT
    }

    /** False when Android first needs the user to allow this app to install apps. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install apps. */
    fun requestPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, name: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageInfo(name, PackageManager.GET_SIGNATURES)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners?.toList()
        else info.signatures?.toList()
        return raw.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
