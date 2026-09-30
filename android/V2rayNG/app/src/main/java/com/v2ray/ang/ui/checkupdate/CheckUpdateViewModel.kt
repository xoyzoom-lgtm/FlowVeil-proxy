package com.v2ray.ang.ui.checkupdate

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.ApkUpdateInstaller
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.UpdateNotifier
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {

    private val _checkPreRelease = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
    )
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()

    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    fun toggleCheckPreRelease(enabled: Boolean) {
        _checkPreRelease.value = enabled
        MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled)
    }

    fun checkForUpdates() {
        launchLoading {
            try {
                val result = UpdateCheckerManager.checkForUpdate(_checkPreRelease.value)
                if (result.hasUpdate) {
                    _updateResult.value = result
                    _showUpdateDialog.value = true
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to check for updates", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    /** null = not downloading, -1 = size unknown, 0..100 = percent. */
    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    private var downloadJob: Job? = null
    private var downloadedApk: File? = null
    private var awaitingInstallPermission = false

    /** Called on resume: continue installing after the user allowed installs from FlowVeil. */
    fun resumeInstallIfPending() {
        if (awaitingInstallPermission && !ApkUpdateInstaller.needsInstallPermission(getApplication())) {
            awaitingInstallPermission = false
            installDownloaded()
        }
    }

    /** Downloads the APK inside the app, then opens the system installer. */
    fun downloadAndInstall() {
        val url = _updateResult.value?.downloadUrl ?: return
        if (downloadJob?.isActive == true) return
        val app = getApplication<Application>()
        downloadJob = viewModelScope.launch {
            _downloadProgress.value = 0
            try {
                val apk = ApkUpdateInstaller.download(app, url) { _downloadProgress.value = it }
                val result = _updateResult.value
                // false = the file differs from the published sum: never install it. null = no sums published: do not block.
                if (ApkUpdateInstaller.verify(apk, result?.assetName, result?.sumsUrl) == false) {
                    apk.delete()
                    _downloadProgress.value = null
                    toastError(R.string.update_checksum_failed)
                    return@launch
                }
                downloadedApk = apk
                _downloadProgress.value = null
                _showUpdateDialog.value = false
                installDownloaded()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Update download failed", e)
                _downloadProgress.value = null
                toastError(R.string.update_download_failed)
            }
        }
    }

    /** Starts the installer for an already downloaded APK (also after granting the permission). */
    fun installDownloaded(): Boolean {
        val apk = downloadedApk ?: return false
        val app = getApplication<Application>()
        if (ApkUpdateInstaller.needsInstallPermission(app)) {
            toastError(R.string.update_allow_install)
            awaitingInstallPermission = true
            ApkUpdateInstaller.openInstallPermissionSettings(app)
            return true
        }
        ApkUpdateInstaller.install(app, apk)
        return true
    }

    /** "Later": the dialog closes and the reminder comes back in 3 days. */
    fun later() {
        UpdateNotifier.later()
        dismissUpdateDialog()
    }

    /** "Skip this version": no notification or banner for it; the next build brings them back. */
    fun skipVersion() {
        _updateResult.value?.build?.takeIf { it > 0 }?.let { UpdateNotifier.skip(it) }
        dismissUpdateDialog()
    }

    fun dismissUpdateDialog() {
        downloadJob?.cancel()
        _downloadProgress.value = null
        _showUpdateDialog.value = false
    }
}
