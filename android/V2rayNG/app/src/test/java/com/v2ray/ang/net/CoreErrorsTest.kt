package com.v2ray.ang.net

import com.v2ray.ang.net.CoreErrors.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreErrorsTest {
    @Test
    fun plainVlessIsRecognisedInsideTheLongXrayMessage() {
        val raw = "config error: infra/conf/serial: failed to parse json config > infra/conf: failed to build outbound config with tag proxy > " +
            "infra/conf: vless without TLS or other encryption is prohibited unless the server address is a private IP or domain"
        assertEquals(Kind.PLAIN_VLESS, CoreErrors.classify(raw))
    }

    @Test
    fun plainTrojanIsRecognised() {
        assertEquals(Kind.PLAIN_TROJAN, CoreErrors.classify("trojan without TLS is prohibited unless the server address is a private IP or domain"))
    }

    @Test
    fun otherErrorsAreLeftAlone() {
        assertEquals(Kind.OTHER, CoreErrors.classify("Core failed to start"))
        assertEquals(Kind.OTHER, CoreErrors.classify(null))
        assertEquals(Kind.OTHER, CoreErrors.classify(""))
    }
}
