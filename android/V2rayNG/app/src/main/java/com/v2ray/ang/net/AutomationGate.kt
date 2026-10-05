package com.v2ray.ang.net

/**
 * Commands from automation apps (Tasker and the like) arrive as a broadcast any installed app could send. They are obeyed
 * only when the user turned automation on AND the command carries the random key that FlowVeil put into the task when the
 * user created it. Without both, nothing happens (stop included).
 */
object AutomationGate {
    fun allowed(enabled: Boolean, storedKey: String?, givenKey: String?): Boolean =
        enabled && !storedKey.isNullOrBlank() && storedKey.length >= 16 && storedKey == givenKey
}
