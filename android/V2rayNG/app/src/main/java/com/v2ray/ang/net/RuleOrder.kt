package com.v2ray.ang.net

/** Routing rules FlowVeil manages itself sit at the top in a fixed order, above the user's or the preset's rules. */
object RuleOrder {
    /** [list] without any item whose id is in [ownIds], with [top] put first (in that order). */
    fun <T> withTop(list: List<T>, idOf: (T) -> String, ownIds: Set<String>, top: List<T>): List<T> =
        top + list.filter { idOf(it) !in ownIds }
}
