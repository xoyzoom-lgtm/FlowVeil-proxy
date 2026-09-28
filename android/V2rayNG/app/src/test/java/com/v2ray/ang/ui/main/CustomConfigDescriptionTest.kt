package com.v2ray.ang.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomConfigDescriptionTest {

    @Test
    fun describesVlessRealityOverRaw() {
        val json = """
            {"outbounds":[
              {"tag":"proxy","protocol":"vless","streamSettings":{"network":"raw","security":"reality"}},
              {"tag":"direct","protocol":"freedom"}
            ]}
        """.trimIndent()
        assertEquals("VLESS / TCP / REALITY | JSON", describeCustomConfig(json))
    }

    @Test
    fun describesHysteriaWhenProxyIsNotFirst() {
        val json = """
            {"outbounds":[
              {"tag":"direct","protocol":"freedom"},
              {"tag":"out","protocol":"hysteria","streamSettings":{"network":"hysteria","security":"tls"}}
            ]}
        """.trimIndent()
        assertEquals("HYSTERIA / HYSTERIA / TLS | JSON", describeCustomConfig(json))
    }

    @Test
    fun omitsMissingTransportAndNoneSecurity() {
        val json = """{"outbounds":[{"protocol":"trojan","streamSettings":{"security":"none"}}]}"""
        assertEquals("TROJAN | JSON", describeCustomConfig(json))
    }

    @Test
    fun returnsNullForInvalidOrProxylessJson() {
        assertNull(describeCustomConfig(null))
        assertNull(describeCustomConfig(""))
        assertNull(describeCustomConfig("not json"))
        assertNull(describeCustomConfig("""{"outbounds":[{"protocol":"freedom"}]}"""))
        assertNull(describeCustomConfig("""{"inbounds":[]}"""))
    }
}
