package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test
    fun ordinaryHostsAndIps() {
        listOf("de.example.com", "1.2.3.4", "10.0.0.1", "localhost", "server.xyz", "a-b.c-d.io", "example.com.").forEach {
            assertTrue(it, ServerAddress.isValid(it))
        }
    }

    @Test
    fun hostsAndroidPatternsRejected() {
        // new and long TLDs, IDN in Unicode and in punycode, underscores, digits-first labels, IPv6 with and without brackets
        listOf(
            "node1.provider.cloud", "srv.antizhalosti.net", "сервер.рф", "xn--e1afmkfd.xn--p1ai", "my_node.example.com",
            "1node.example.dev", "2001:db8::1", "[2001:db8::1]", "::1", "fe80::1%wlan0", "a.b.c.d.e.f.g.example.technology",
        ).forEach { assertTrue(it, ServerAddress.isValid(it)) }
    }

    @Test
    fun whatIsReallyBroken() {
        assertEquals(ServerAddress.Problem.EMPTY, ServerAddress.problem(""))
        assertEquals(ServerAddress.Problem.EMPTY, ServerAddress.problem(null))
        assertEquals(ServerAddress.Problem.SPACES, ServerAddress.problem("de example.com"))
        assertEquals(ServerAddress.Problem.SCHEME_OR_PATH, ServerAddress.problem("https://de.example.com"))
        assertEquals(ServerAddress.Problem.SCHEME_OR_PATH, ServerAddress.problem("de.example.com/path"))
        assertEquals(ServerAddress.Problem.BAD_HOST, ServerAddress.problem("-bad.example.com"))
        assertEquals(ServerAddress.Problem.BAD_HOST, ServerAddress.problem("a..b"))
        assertEquals(ServerAddress.Problem.BAD_HOST, ServerAddress.problem("999.1.1.1"))
        assertEquals(ServerAddress.Problem.BAD_HOST, ServerAddress.problem("[2001:db8::1"))
        assertEquals(ServerAddress.Problem.BAD_HOST, ServerAddress.problem("x".repeat(64) + ".com"))
        assertNull(ServerAddress.problem("  de.example.com "))
        assertFalse(ServerAddress.isValid("bad!host.com"))
    }
}
