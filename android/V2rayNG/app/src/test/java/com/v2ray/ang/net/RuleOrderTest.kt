package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleOrderTest {
    @Test
    fun ownRulesAreReplacedAndPutFirst() {
        val existing = listOf("direct-sites", "fv-adblock", "preset-1", "fv-telegram-ips", "preset-2")
        val own = setOf("fv-adblock", "fv-telegram-domains", "fv-telegram-ips")
        assertEquals(listOf("fv-adblock", "fv-telegram-domains", "fv-telegram-ips", "direct-sites", "preset-1", "preset-2"),
            RuleOrder.withTop(existing, { it }, own, listOf("fv-adblock", "fv-telegram-domains", "fv-telegram-ips")))
        // both switches off: our rules disappear, nothing else moves
        assertEquals(listOf("direct-sites", "preset-1", "preset-2"), RuleOrder.withTop(existing, { it }, own, emptyList()))
    }
}
