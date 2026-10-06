package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubLookTest {
    @Test
    fun roundTrip() {
        val look = SubLook(SubLook.Avatar.EMOJI, "🚀", 3, true, 17L)
        assertEquals(look, SubLook.decode(look.encode()))
        val photo = SubLook(SubLook.Avatar.PHOTO, "", -1, false, 5L)
        assertEquals(photo, SubLook.decode(photo.encode()))
        assertTrue(SubLook().isDefault)
        assertEquals(SubLook(), SubLook.decode(SubLook().encode()))
    }

    @Test
    fun brokenInputFallsBack() {
        assertEquals(SubLook(), SubLook.decode(null))
        assertEquals(SubLook(), SubLook.decode("???"))
        assertEquals(SubLook(), SubLook.decode("avatar=emoji")) // emoji missing
        assertEquals(-1, SubLook.decode("avatar=none;grad=99").gradient)
        assertEquals(-1, SubLook.decode("grad=abc").gradient)
        assertEquals(SubLook.Avatar.NONE, SubLook.decode("avatar=emoji;emoji=abc").avatar) // letters are not an emoji
    }

    @Test
    fun emojiCleaning() {
        assertEquals("🚀", SubLook.cleanEmoji(" 🚀 "))
        assertEquals("", SubLook.cleanEmoji("a"))
        assertEquals("", SubLook.cleanEmoji("1"))
        assertEquals("", SubLook.cleanEmoji("🚀;grad=1"))
        assertEquals("", SubLook.cleanEmoji("🚀🚀🚀🚀🚀"))
        SubLook.EMOJIS.forEach { assertEquals(it, SubLook.cleanEmoji(it)) }
    }

    @Test
    fun telegramNames() {
        assertEquals("FlowVeil", TelegramAvatar.username("https://t.me/FlowVeil"))
        assertEquals("FlowVeil", TelegramAvatar.username("t.me/FlowVeil/"))
        assertEquals("FlowVeil", TelegramAvatar.username("@FlowVeil"))
        assertEquals("my_bot", TelegramAvatar.username("my_bot"))
        assertEquals("FlowVeil", TelegramAvatar.username("https://t.me/FlowVeil?start=x"))
        assertNull(TelegramAvatar.username("https://t.me/+AbCdEf"))
        assertNull(TelegramAvatar.username("https://t.me/c/123/4"))
        assertNull(TelegramAvatar.username("abc"))
        assertNull(TelegramAvatar.username("https://evil.example/FlowVeil"))
        assertNull(TelegramAvatar.username("a b c d"))
        assertNull(TelegramAvatar.username(null))
    }

    @Test
    fun telegramPicture() {
        val html = """<meta property="og:image" content="https://cdn4.telesco.pe/file/abc.jpg?x=1&amp;y=2">"""
        assertEquals("https://cdn4.telesco.pe/file/abc.jpg?x=1&y=2", TelegramAvatar.ogImage(html))
        assertNull(TelegramAvatar.ogImage("""<meta property="og:image" content="https://telegram.org/img/t_logo.png">"""))
        assertNull(TelegramAvatar.ogImage("""<meta property="og:image" content="https://evil.example/a.jpg">"""))
        assertNull(TelegramAvatar.ogImage("""<meta property="og:image" content="http://cdn4.telesco.pe/a.jpg">"""))
        assertNull(TelegramAvatar.ogImage("""<meta property="og:image" content="https://telesco.pe.evil.example/a.jpg">"""))
        assertNull(TelegramAvatar.ogImage("<html></html>"))
        assertNull(TelegramAvatar.ogImage(null))
        assertTrue(TelegramAvatar.allowedImageUrl("https://cdn1.cdn-telegram.org/x.jpg"))
        assertFalse(TelegramAvatar.allowedImageUrl("https://nottelegram.org/x.jpg"))
    }
}
