package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages global flags for actions triggered by setting changes.
 * Uses AtomicBoolean for thread-safe consume operations.
 */
object SettingsChangeManager {

    private val restartService = AtomicBoolean(false)
    private val setupGroupTab = AtomicBoolean(false)

    // Keys that affect only UI behavior and do not require core service restart.
    private val uiOnlyKeys = setOf(
        AppConfig.PREF_CONFIRM_REMOVE,
        AppConfig.PREF_DOUBLE_COLUMN_DISPLAY,
        AppConfig.PREF_GROUP_ALL_DISPLAY,
        AppConfig.PREF_LANGUAGE,
        AppConfig.PREF_UI_MODE_NIGHT,
        AppConfig.PREF_DYNAMIC_COLOR,
        AppConfig.PREF_IS_BOOTED,
        AppConfig.PREF_HAPP_THEME_ID,
        AppConfig.PREF_HAPP_THEME_CUSTOM_JSON,
        AppConfig.PREF_SEND_HWID,
        AppConfig.PREF_AUTO_FAILOVER,
        AppConfig.PREF_SUB_REMINDERS,
        AppConfig.PREF_SHOW_BEST_BUTTON,
        AppConfig.PREF_DEV_MODE,
        AppConfig.PREF_SUB_UPDATE_INTERVAL,
        WhitelistBypass.PREF_ENABLED,
        WhitelistBypass.PREF_MODE,
        WhitelistBypass.PREF_SERVERS,
        WhitelistBypass.PREF_AUTO_RETURN,
    )

    /**
     * Called when a setting value changes.
     * Triggers service restart if the key is not UI-only, and always refreshes UI tabs.
     */
    fun notifySettingChanged(key: String) {
        if (key !in uiOnlyKeys) {
            makeRestartService()
        }
        makeSetupGroupTab()
    }

    fun makeRestartService() {
        restartService.set(true)
    }

    /**
     * Atomically consumes the restart flag.
     * @return true if a restart was requested, false otherwise.
     */
    fun consumeRestartService(): Boolean =
        restartService.compareAndSet(true, false)

    fun makeSetupGroupTab() {
        setupGroupTab.set(true)
    }

    /**
     * Atomically consumes the setup-group-tab flag.
     * @return true if UI refresh was requested, false otherwise.
     */
    fun consumeSetupGroupTab(): Boolean =
        setupGroupTab.compareAndSet(true, false)
}
