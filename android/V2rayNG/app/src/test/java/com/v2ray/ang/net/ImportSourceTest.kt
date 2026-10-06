package com.v2ray.ang.net

import com.v2ray.ang.net.ImportSource.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSourceTest {
    @Test
    fun happAddUnwrapped() {
        assertEquals(Result.Text("https://sub.example.com/abc"), ImportSource.normalize("happ://add/https://sub.example.com/abc"))
        assertEquals(Result.Text("https://sub.example.com/abc?x=1"), ImportSource.normalize("happ://add/https%3A%2F%2Fsub.example.com%2Fabc%3Fx%3D1"))
        assertEquals(Result.Empty, ImportSource.normalize("happ://add/"))
    }

    @Test
    fun happCryptNotOpened() {
        assertEquals(Result.HappEncrypted, ImportSource.normalize("happ://crypt/AAAA"))
        assertEquals(Result.HappEncrypted, ImportSource.normalize("HAPP://crypt3/xyz"))
    }

    @Test
    fun v2rayngAndFlowveil() {
        assertEquals(Result.Text("https://a.example/s"), ImportSource.normalize("v2rayng://install-sub?url=https%3A%2F%2Fa.example%2Fs"))
        assertEquals(Result.Text("https://a.example/s#Name"), ImportSource.normalize("flowveil://add?url=https://a.example/s&name=Name"))
    }

    @Test
    fun othersUnchanged() {
        assertEquals(Result.Text("vless://id@host:443#x"), ImportSource.normalize("  vless://id@host:443#x \n"))
        assertEquals(Result.Text("https://x.example/sub"), ImportSource.normalize("https://x.example/sub"))
        assertEquals(Result.Empty, ImportSource.normalize("   "))
        assertEquals(Result.Empty, ImportSource.normalize(null))
    }
}
