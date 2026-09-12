package com.personallifeos.mobile.updates

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class AppRelease(
    val schemaVersion: Int,
    val versionCode: Long,
    val versionName: String,
    val apkPath: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
)

enum class UpdatePhase { Idle, Checking, Current, Available, Downloading, Ready, Error }

data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val release: AppRelease? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val apkFile: File? = null,
    val message: String? = null,
)
