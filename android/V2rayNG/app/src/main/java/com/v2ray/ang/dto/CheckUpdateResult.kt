package com.v2ray.ang.dto

data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val error: String? = null,
    val isPreRelease: Boolean = false,
    val build: Int = 0,
    val assetName: String? = null,
    /** `SHA256SUMS.txt` of the release when it has one (the download is checked against it). */
    val sumsUrl: String? = null,
    val manifestUrl: String? = null,
    val signatureUrl: String? = null,
)