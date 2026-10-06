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

    @Test
    fun otherAppsWrappers() {
        val link = "https://sub.example.com/abc/def"
        val enc = "https%3A%2F%2Fsub.example.com%2Fabc%2Fdef"
        assertEquals(Result.Text(link), ImportSource.normalize("hiddify://import/$link"))
        assertEquals(Result.Text("$link#Name"), ImportSource.normalize("hiddify://import/$link#Name"))
        assertEquals(Result.Text(link), ImportSource.normalize("v2raytun://import/$link"))
        assertEquals(Result.Text(link), ImportSource.normalize("streisand://import/$link"))
        assertEquals(Result.Text(link), ImportSource.normalize("clash://install-config?url=$enc&name=x"))
        assertEquals(Result.Text(link), ImportSource.normalize("stash://install-config?url=$enc"))
        assertEquals(Result.Text(link), ImportSource.normalize("sing-box://import-remote-profile?url=$enc#Name"))
        assertEquals(Result.Text(link), ImportSource.normalize("karing://install-config?url=$enc"))
        assertEquals(Result.Text(link), ImportSource.normalize("v2box://install-sub?url=$enc&name=x"))
        assertEquals(Result.Text(link), ImportSource.normalize("someapp://import/$enc"))
        val b64 = java.util.Base64.getEncoder().encodeToString(link.toByteArray())
        assertEquals(Result.Text(link), ImportSource.normalize("sub://$b64"))
    }

    @Test
    fun serverLinksAndGarbageUntouched() {
        val vless = "vless://id@host:443?security=tls&host=https://x.example#n"
        assertEquals(Result.Text(vless), ImportSource.normalize(vless))
        assertEquals(Result.Text("myapp://nothing/here"), ImportSource.normalize("myapp://nothing/here"))
        assertEquals(Result.Text("sub://@@@"), ImportSource.normalize("sub://@@@"))
        assertEquals(Result.Text("vmess://abc\nvmess://def"), ImportSource.normalize("vmess://abc\nvmess://def"))
    }
}
