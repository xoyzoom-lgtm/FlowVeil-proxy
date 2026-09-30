package com.v2ray.ang.net

/**
 * Which servers to try when the current one stops working (the same rules as the Windows app): the current
 * subscription first, then the other usable ones in list order; inside a group servers that answered before
 * come first (fastest first), then untested ones in list order, servers that failed their last test last.
 * The current server and servers in Russia are never offered.
 */
object FailoverPlan {
    const val PER_GROUP = 12

    data class Server(val guid: String, val subId: String, val remarks: String, val delayMs: Long, val order: Int)

    fun groups(
        all: List<Server>,
        currentGuid: String,
        currentSubId: String,
        subOrder: List<String>,
        subUsable: (String) -> Boolean,
        isRussian: (String?) -> Boolean,
        acrossSubscriptions: Boolean,
        perGroup: Int = PER_GROUP,
    ): List<List<String>> {
        val subs = buildList {
            if (subUsable(currentSubId)) add(currentSubId)
            if (acrossSubscriptions) addAll(subOrder.filter { it != currentSubId && subUsable(it) })
        }
        return subs.map { subId ->
            all.filter { it.subId == subId && it.guid != currentGuid && !isRussian(it.remarks) }
                .sortedWith(
                    compareBy<Server>({ if (it.delayMs > 0) 0 else if (it.delayMs == 0L) 1 else 2 })
                        .thenBy { if (it.delayMs > 0) it.delayMs else 0L }
                        .thenBy { it.order }
                )
                .take(perGroup)
                .map { it.guid }
        }.filter { it.isNotEmpty() }
    }
}
