package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerNameTest {
    @Test
    fun dropsSpeedAndProtocolMarks() {
        assertEquals("Frankfurt", ServerName.clean("[VIP] Frankfurt | 100 Mbps"))
        assertEquals("Germany · Frankfurt 01", ServerName.clean("Germany | Frankfurt 01 (VLESS Reality)"))
        assertEquals("Amsterdam", ServerName.clean("Amsterdam [TCP] [CDN]"))
        assertEquals("Москва", ServerName.clean("Москва - 500 мбит/с"))
    }

    @Test
    fun keepsRealNamesAndNumbers() {
        assertEquals("DE-01", ServerName.clean("DE-01"))
        assertEquals("Game Server 2", ServerName.clean("Game Server 2"))
        assertEquals("Prague", ServerName.clean("Prague"))
        assertEquals("Provence", ServerName.clean("Provence"))
    }

    @Test
    fun neverEmpty() {
        assertEquals("VIP", ServerName.clean("VIP"))
        assertEquals("[VIP]", ServerName.clean("[VIP]"))
        assertEquals("", ServerName.clean(""))
    }
}
