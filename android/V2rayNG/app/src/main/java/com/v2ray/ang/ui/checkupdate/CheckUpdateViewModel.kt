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
import com.v2ray.ang.net.UpdateManifest
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** One line of "what was checked about the downloaded file": [ok] = passed, otherwise a note the user should read. */
data class UpdateCheck(val ok: Boolean, val textRes: Int)

/** The downloaded file as the sheet shows it: its SHA-256 and what was checked. Installing waits for the user's tap. */
data class DownloadedUpdate(val sha256: String, val checks: List<UpdateCheck>)

class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {

    private val _downloaded = MutableStateFlow<DownloadedUpdate?>(null)
    val downloaded: StateFlow<DownloadedUpdate?> = _downloaded.asStateFlow()

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

    /** [autoInstall]: opened from the "update available" pop-up, so the download starts right away. */
    fun checkForUpdates(autoInstall: Boolean = false) {
        launchLoading {
            try {
                val result = UpdateCheckerManager.checkForUpdate(_checkPreRelease.value)
                if (result.hasUpdate) {
                    _updateResult.value = result
                    _showUpdateDialog.value = true
                    if (autoInstall) downloadAndInstall()
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

    private val _downloadFailed = MutableStateFlow(false)
    /** The in-app download did not work: the dialog then offers the browser. */
    val downloadFailed: StateFlow<Boolean> = _downloadFailed.asStateFlow()

    private val _updateError = MutableStateFlow<Int?>(null)
    /** Why the downloaded file was not installed. Stays on the dialog: a toast is gone before it can be read. */
    val updateError: StateFlow<Int?> = _updateError.asStateFlow()

    private val _errorDetail = MutableStateFlow<String?>(null)
    /** The technical reason of the last failure (exception and message), shown on the dialog so it can be reported. */
    val errorDetail: StateFlow<String?> = _errorDetail.asStateFlow()

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
            _downloadFailed.value = false
            _updateError.value = null
            _errorDetail.value = null
            try {
                val apk = ApkUpdateInstaller.download(app, url) { _downloadProgress.value = it }
                val result = _updateResult.value
                // An old-style SHA256SUMS that disagrees: never install.
                val sums = runCatching { ApkUpdateInstaller.verify(apk, result?.assetName, result?.sumsUrl) }
                    .onFailure { LogUtil.e(AppConfig.TAG, "Update checksum step failed", it) }.getOrNull()
                if (sums == false) {
                    reject(apk, R.string.update_checksum_failed)
                    return@launch
                }
                val signer = ApkUpdateInstaller.sameSigner(app, apk)
                if (signer == false) {
                    reject(apk, R.string.update_other_signer)
                    return@launch
                }
                val check = if (result != null) {
                    runCatching { ApkUpdateInstaller.checkManifest(apk, result) }
                        .onFailure { LogUtil.e(AppConfig.TAG, "Update manifest step failed", it) }
                        .getOrDefault(UpdateManifest.Result.Missing)
                } else UpdateManifest.Result.Missing
                if (check is UpdateManifest.Result.Rejected) {
                    LogUtil.w(AppConfig.TAG, "Update refused: ${check.reason}")
                    reject(apk, rejectText(check.reason))
                    return@launch
                }
                downloadedApk = apk
                _downloadProgress.value = null
                // Shown on the sheet as it is: what matched, what could not be checked. The user installs with one tap.
                val checks = buildList {
                    add(if (sums == true) UpdateCheck(true, R.string.update_check_sha_ok) else UpdateCheck(false, R.string.update_check_sha_none))
                    add(if (signer == true) UpdateCheck(true, R.string.update_check_signer_ok) else UpdateCheck(false, R.string.update_check_signer_none))
                    when {
                        check is UpdateManifest.Result.Verified && check.signed -> add(UpdateCheck(true, R.string.update_check_manifest_signed))
                        check is UpdateManifest.Result.Verified -> {
                            add(UpdateCheck(true, R.string.update_check_manifest_listed))
                            add(UpdateCheck(false, R.string.update_check_manifest_unsigned))
                        }
                        else -> add(UpdateCheck(false, R.string.update_check_manifest_none))
                    }
                }
                _downloaded.value = DownloadedUpdate(ApkUpdateInstaller.sha256(apk), checks)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Update download failed", e)
                _downloadProgress.value = null
                _downloadFailed.value = true
                val why = (e as? ApkUpdateInstaller.DownloadFailed)?.reason?.take(80)
                _errorDetail.value = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(160)
                _updateError.value = R.string.update_download_failed
                if (why != null) toastError(app.getString(R.string.update_download_failed_why, why)) else toastError(R.string.update_download_failed)
            }
        }
    }

    private fun reject(apk: File, message: Int) {
        apk.delete()
        _downloadProgress.value = null
        _updateError.value = message
        toastError(message)
    }

    private fun rejectText(reason: UpdateManifest.Reason): Int = when (reason) {
        UpdateManifest.Reason.BAD_SIGNATURE -> R.string.update_bad_signature
        UpdateManifest.Reason.ROLLBACK -> R.string.update_rollback
        UpdateManifest.Reason.UNSIGNED -> R.string.update_unsigned_refused
        else -> R.string.update_checksum_failed
    }

    /** Starts the installer for an already downloaded APK (also after granting the permission). */
    fun installDownloaded(): Boolean {
        val apk = downloadedApk ?: return false
        val app = getApplication<Application>()
        if (ApkUpdateInstaller.needsInstallPermission(app)) {
            toastError(R.string.update_allow_install)
            awaitingInstallPermission = true
            try {
                ApkUpdateInstaller.openInstallPermissionSettings(app)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Cannot open the install permission screen", e)
                awaitingInstallPermission = false
                _errorDetail.value = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(160)
                _updateError.value = R.string.update_install_failed
                _showUpdateDialog.value = true
                return false
            }
            return true
        }
        try {
            ApkUpdateInstaller.install(app, apk)
        } catch (e: Exception) {
            // The system installer did not open: say so on the dialog, with the way around it.
            LogUtil.e(AppConfig.TAG, "Cannot start the installer", e)
            _errorDetail.value = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(160)
            _updateError.value = R.string.update_install_failed
            _showUpdateDialog.value = true
            return false
        }
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
        if (_downloaded.value != null) downloadedApk?.delete().also { downloadedApk = null }
        _downloaded.value = null
        _downloadProgress.value = null
        _showUpdateDialog.value = false
    }
}
