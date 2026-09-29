package com.v2ray.ang.fmt

import android.util.Log
import com.v2ray.ang.enums.EConfigType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic

class TuicFmtTest {

    private lateinit var mockLog: MockedStatic<Log>

    @Before
    fun setUp() {
        mockLog = mockStatic(Log::class.java, Mockito.RETURNS_DEFAULTS)
    }

    @After
    fun tearDown() {
        mockLog.close()
    }

    @Test
    fun parsesFullLink() {
        val item = TuicFmt.parse(
            "tuic://11111111-2222-3333-4444-555555555555:p%40ss:word@example.com:8443" +
                "?congestion_control=cubic&udp_relay_mode=quic&alpn=h3&sni=cdn.example.com&allow_insecure=1#My%20TUIC"
        )
        assertNotNull(item)
        item!!
        assertEquals(EConfigType.TUIC, item.configType)
        assertEquals("example.com", item.server)
        assertEquals("8443", item.serverPort)
        assertEquals("11111111-2222-3333-4444-555555555555", item.username)
        assertEquals("p@ss:word", item.password)
        assertEquals("cubic", item.congestionControl)
        assertEquals("quic", item.udpRelayMode)
        assertEquals("cdn.example.com", item.sni)
        assertEquals("h3", item.alpn)
        assertEquals(true, item.insecure)
        assertEquals("My TUIC", item.remarks)
    }

    @Test
    fun appliesDefaultsAndIgnoresUnknownValues() {
        val item = TuicFmt.parse("tuic://uuid:pw@1.2.3.4:443?congestion_control=weird&udp_relay_mode=foo#x")!!
        assertEquals("bbr", item.congestionControl)
        assertEquals("native", item.udpRelayMode)
        assertEquals("h3", item.alpn)
        assertEquals(false, item.insecure)
    }

    @Test
    fun linkWithoutUuidIsRejected() {
        assertNull(TuicFmt.parse("tuic://@example.com:443#x"))
    }

    @Test
    fun roundTripKeepsTheFields() {
        val original = TuicFmt.parse(
            "tuic://uuid-1:secret@host.example:443?congestion_control=new_reno&udp_relay_mode=quic&sni=s.example#name"
        )!!
        val again = TuicFmt.parse("tuic://" + TuicFmt.toUri(original))!!
        assertEquals(original.server, again.server)
        assertEquals(original.serverPort, again.serverPort)
        assertEquals(original.username, again.username)
        assertEquals(original.password, again.password)
        assertEquals(original.congestionControl, again.congestionControl)
        assertEquals(original.udpRelayMode, again.udpRelayMode)
        assertEquals(original.sni, again.sni)
        assertEquals(original.remarks, again.remarks)
        assertTrue(TuicFmt.toUri(original).contains("congestion_control=new_reno"))
    }
}
