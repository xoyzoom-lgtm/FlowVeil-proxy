package com.v2ray.ang.net

import com.v2ray.ang.net.FailoverPlan.Server
import org.junit.Assert.assertEquals
import org.junit.Test

class FailoverPlanTest {
    private val all = listOf(
        Server("a1", "A", "Нидерланды", 120, 0), Server("a2", "A", "Польша", 80, 1), Server("a3", "A", "Москва RU", 30, 2),
        Server("a4", "A", "Германия", 0, 3), Server("a5", "A", "Финляндия", -1, 4),
        Server("b1", "B", "Франция", 50, 0), Server("c1", "C", "Турция", 10, 0),
    )
    private val ru = { n: String? -> n?.contains("RU") == true }

    @Test
    fun currentSubFirstThenOthers() {
        val g = FailoverPlan.groups(all, "a1", "A", listOf("A", "B", "C"), { it != "C" }, ru, true)
        assertEquals(listOf(listOf("a2", "a4", "a5"), listOf("b1")), g)
    }

    @Test
    fun otherSubscriptionsOff() {
        val g = FailoverPlan.groups(all, "a1", "A", listOf("A", "B"), { true }, ru, false)
        assertEquals(listOf(listOf("a2", "a4", "a5")), g)
    }

    @Test
    fun unusableCurrentSubscription() {
        val g = FailoverPlan.groups(all, "a1", "A", listOf("A", "B"), { it == "B" }, ru, true)
        assertEquals(listOf(listOf("b1")), g)
    }
}
