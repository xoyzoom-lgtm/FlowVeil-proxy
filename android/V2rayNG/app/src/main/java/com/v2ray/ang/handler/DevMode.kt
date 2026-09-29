package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

/**
 * Developer mode: off by default so everyday users only see what they need. When on, the
 * technical menu items and settings (manual server editors, core/DNS/routing options, logs,
 * bulk tools) appear.
 */
object DevMode {
    fun isOn(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_DEV_MODE, false)
}
