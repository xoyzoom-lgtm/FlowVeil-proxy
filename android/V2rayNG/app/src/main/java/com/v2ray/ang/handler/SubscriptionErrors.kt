package com.v2ray.ang.handler

import com.v2ray.ang.net.SubIssue
import java.util.concurrent.ConcurrentHashMap

/** Why the last update of a subscription got no servers, in plain words for the UI. */
object SubscriptionErrors {
    private val errors = ConcurrentHashMap<String, String>()

    /** Machine-readable twin of the text, kept in MMKV so the diagnosis (another process) can read it. */
    private fun issueKey(subId: String) = "sub_issue_$subId"

    fun issue(subId: String): SubIssue = SubIssue.fromId(MmkvManager.decodeSettingsString(issueKey(subId)))

    fun record(subId: String, reason: String, issue: SubIssue = SubIssue.UNREACHABLE) {
        MmkvManager.encodeSettings(issueKey(subId), issue.name)
        // Name the subscription, so with several of them the user knows which one failed.
        val name = MmkvManager.decodeSubscription(subId)?.let { it.profileTitle?.takeIf { t -> t.isNotBlank() } ?: it.remarks }
        errors[subId] = if (name.isNullOrBlank()) reason else "«$name»: $reason"
    }

    fun clear(subId: String) {
        errors.remove(subId)
        MmkvManager.encodeSettings(issueKey(subId), "")
    }

    fun get(subId: String): String? = errors[subId]

    fun latest(subIds: Collection<String>): String? = subIds.firstNotNullOfOrNull { errors[it] }
}
