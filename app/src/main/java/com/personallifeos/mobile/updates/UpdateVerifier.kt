package com.personallifeos.mobile.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import android.util.Base64

internal interface ReleaseVerifier {
    fun verify(release: AppRelease, file: File)
}

internal class UpdateVerifier(private val context: Context) : ReleaseVerifier {
    override fun verify(release: AppRelease, file: File) {
        require(file.length() == release.sizeBytes) { "Размер APK не совпадает" }
        require(file.length() <= 150L * 1024 * 1024) { "APK слишком большой" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256.lowercase()) {
            "Контрольная сумма APK не совпадает"
        }
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, packageFlags())
            ?: error("Файл APK повреждён")
        require(archive.packageName == context.packageName) { "APK предназначен для другого приложения" }
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) {
            "APK требует более новую версию Android"
        }
        require(packageVersionCode(archive) == release.versionCode) { "Версия APK не совпадает" }
        val installed = pm.getPackageInfo(context.packageName, packageFlags())
        val candidateSignatures = signatures(archive)
        val installedSignatures = signatures(installed)
        require(candidateSignatures.isNotEmpty() && installedSignatures.isNotEmpty() &&
            candidateSignatures.toSet() == installedSignatures.toSet()) { "Подпись APK не совпадает" }
    }

    private fun packageFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else PackageManager.GET_SIGNATURES

    private fun signatures(info: PackageInfo): List<String> {
        val values = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }.orEmpty()
        }
        return values
    }

    private fun packageVersionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
}
