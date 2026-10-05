package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerIdentityTest {
    private fun fp(vararg p: String) = ServerIdentity.fingerprint(p.toList())
    private fun e(id: String, fp: String, name: String) = IdEntry(id, fp, ServerIdentity.nameKey(name))

    private val de = fp("vless", "de.example", "443", "uuid-1", "tcp", "reality", "sni", "pk")
    private val nl = fp("vless", "nl.example", "443", "uuid-1", "tcp", "reality", "sni", "pk")
    private val pl = fp("hysteria2", "pl.example", "8443", "pass", "", "tls", "", "")

    @Test
    fun theSameServersKeepTheirIdsWhateverTheOrder() {
        val old = listOf(e("o1", de, "Германия"), e("o2", nl, "Нидерланды"), e("o3", pl, "Польша | Игровой 🔥"))
        val new = listOf(e("n1", pl, "Польша | Игровой 🔥"), e("n2", de, "Германия"), e("n3", nl, "Нидерланды"))
        assertEquals(mapOf("n1" to "o3", "n2" to "o1", "n3" to "o2"), ServerIdentity.reuse(new, old))
    }

    @Test
    fun aRenamedServerIsFoundByItsFingerprint() {
        val r = ServerIdentity.reuse(listOf(e("n", de, "🇩🇪 Germany NEW")), listOf(e("o", de, "Германия")))
        assertEquals(mapOf("n" to "o"), r)
    }

    @Test
    fun aMovedServerIsFoundByItsUniqueName() {
        val moved = fp("vless", "de2.example", "443", "uuid-1", "tcp", "reality", "sni", "pk")
        val r = ServerIdentity.reuse(listOf(e("n", moved, "🇩🇪 Германия")), listOf(e("o", de, "Германия")))
        assertEquals(mapOf("n" to "o"), r)
    }

    @Test
    fun twoServersWithOneNameAreNotGuessedByName() {
        val a = fp("x", "a"); val b = fp("x", "b"); val c = fp("x", "c"); val d = fp("x", "d")
        val r = ServerIdentity.reuse(listOf(e("n1", c, "Германия"), e("n2", d, "Германия")), listOf(e("o1", a, "Германия"), e("o2", b, "Германия")))
        assertTrue(r.isEmpty())
    }

    @Test
    fun aRemovedServerIsNotMatchedAndANewOneGetsNothing() {
        val fi = fp("vless", "fi.example")
        val r = ServerIdentity.reuse(listOf(e("n1", de, "Германия"), e("n2", fi, "Финляндия")), listOf(e("o1", de, "Германия"), e("o2", nl, "Нидерланды")))
        assertEquals(mapOf("n1" to "o1"), r)
    }

    @Test
    fun duplicatesInTheSubscriptionUseEachOldIdOnce() {
        val r = ServerIdentity.reuse(listOf(e("n1", de, "Германия"), e("n2", de, "Германия")), listOf(e("o1", de, "Германия")))
        assertEquals(mapOf("n1" to "o1"), r)
    }

    @Test
    fun theNameDoesNotChangeTheFingerprintButTheAddressDoes() {
        assertEquals(fp("vless", "A.example ", "443"), fp("VLESS", "a.example", "443"))
        assertNotEquals(fp("vless", "a.example", "443"), fp("vless", "b.example", "443"))
    }

    @Test
    fun sharedVectorWithWindows() = assertEquals("4dd0918faa9a2cca", fp("vless", "a.example", "443"))

    @Test
    fun nameKeyDropsDecorations() {
        assertEquals("германия | игровой", ServerIdentity.nameKey("🇩🇪  Германия | Игровой 🔥"))
        assertEquals("антижалости.нет", ServerIdentity.nameKey("💀АНТИЖАЛОСТИ.НЕТ💀"))
        assertEquals("", ServerIdentity.nameKey("🔥🔥"))
    }

    @Test
    fun lostRefsRoundTripAndExpire() {
        val now = 1_000_000_000_000L
        val list = listOf(LostRef("g1", "fp1", "германия", "sub&1", "Германия; №1", now - 1000), LostRef("g2", "fp2", "", "s", "x", now - LostRef.KEEP_MS - 1))
        val decoded = LostRef.decode(LostRef.encode(list))
        assertEquals(list, decoded)
        assertEquals(listOf("g1"), LostRef.prune(decoded, now, emptySet()).map { it.id })
        assertTrue(LostRef.prune(decoded, now, setOf("g1")).isEmpty())
    }
}
