package com.v2ray.ang.net

/**
 * "Быстрый режим": fewer extras while connected, nothing about protection or DNS is touched (same rules on Windows,
 * ServiceLib/Handler/FastMode.cs). What it changes:
 *  - the core logs only warnings and errors (less writing, less noise);
 *  - background server checks come half as often (not on a restricted mobile network, where speed of recovery matters more);
 *  - one tap on "Connect" with no server chosen connects to the best one.
 */
object FastMode {
    /** Log level for the core: in fast mode never more than "warning", otherwise the user's own choice. */
    fun logLevel(fast: Boolean, userLevel: String?): String {
        val level = userLevel?.takeIf { it.isNotBlank() } ?: "warning"
        return if (fast && level in setOf("debug", "info")) "warning" else level
    }

    /** Pause between background checks. */
    fun checkInterval(baseMs: Long, fast: Boolean, restrictedMobile: Boolean): Long =
        if (fast && !restrictedMobile) baseMs * 2 else baseMs

    /** True when a tap on "Connect" should first pick the best server. */
    fun connectPicksBest(fast: Boolean, hasChosenServer: Boolean, serverCount: Int): Boolean =
        fast && !hasChosenServer && serverCount > 1
}
