package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortPickTest {
    @Test
    fun freeStaysAsIs() = assertEquals(10808, PortPick.pick(10808) { true })

    @Test
    fun busyMovesUp() {
        val busy = setOf(10808, 10809)
        assertEquals(10810, PortPick.pick(10808) { it !in busy })
    }

    @Test
    fun neighbourCounts() {
        // 10808 is free, but its HTTP neighbour 10809 is not: the pair 10810/10811 is chosen
        val busy = setOf(10809)
        assertEquals(10810, PortPick.pick(10808, listOf(0, 1)) { it !in busy })
    }

    @Test
    fun nothingFree() = assertNull(PortPick.pick(10808) { false })

    @Test
    fun outOfRange() = assertNull(PortPick.pick(900) { false })
}
