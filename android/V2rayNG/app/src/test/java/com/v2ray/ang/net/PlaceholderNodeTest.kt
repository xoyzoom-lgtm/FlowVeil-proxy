package com.v2ray.ang.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderNodeTest {
    @Test
    fun loopbackAndUnspecifiedHostsAreInfoLines() {
        assertTrue(PlaceholderNode.isPlaceholder("127.0.0.1", "443"))
        assertTrue(PlaceholderNode.isPlaceholder("127.1.2.3", "443"))
        assertTrue(PlaceholderNode.isPlaceholder("0.0.0.0", "443"))
        assertTrue(PlaceholderNode.isPlaceholder("localhost", "443"))
        assertTrue(PlaceholderNode.isPlaceholder("[::1]", "443"))
        assertTrue(PlaceholderNode.isPlaceholder("::", "443"))
    }

    @Test
    fun emptyHostOrBadPortIsInfoLine() {
        assertTrue(PlaceholderNode.isPlaceholder(null, "443"))
        assertTrue(PlaceholderNode.isPlaceholder("  ", "443"))
        assertTrue(PlaceholderNode.isPlaceholder("example.com", null))
        assertTrue(PlaceholderNode.isPlaceholder("example.com", ""))
        assertTrue(PlaceholderNode.isPlaceholder("example.com", "0"))
        assertTrue(PlaceholderNode.isPlaceholder("example.com", "70000"))
        assertTrue(PlaceholderNode.isPlaceholder("example.com", "abc"))
    }

    @Test
    fun realServersAreNotInfoLines() {
        assertFalse(PlaceholderNode.isPlaceholder("example.com", "443"))
        assertFalse(PlaceholderNode.isPlaceholder("203.0.113.7", " 8443 "))
        assertFalse(PlaceholderNode.isPlaceholder("2001:db8::1", "443"))
        assertFalse(PlaceholderNode.isPlaceholder("[2001:db8::1]", "443"))
        assertFalse(PlaceholderNode.isPlaceholder("127example.com", "443"))
    }
}
