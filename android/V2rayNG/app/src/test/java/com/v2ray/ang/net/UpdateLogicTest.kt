package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private fun rel(tag: String, vararg assets: String, draft: Boolean = false, pre: Boolean = false, body: String = "") =
        ReleaseInfo(tag, draft, pre, body, assets.map { ReleaseAsset(it, "https://x/$tag/$it") })

    private val arm = listOf("FlowVeil-android-arm64.apk", "FlowVeil-android.apk")

    @Test fun buildOfTag() {
        assertEquals(92, UpdateLogic.buildOf("v0092"))
        assertEquals(92, UpdateLogic.buildOf("build-92"))
        assertEquals(null, UpdateLogic.buildOf("latest"))
    }

    @Test fun picksHighestBuildNotLatestFlag() {
        val list = listOf(rel("v0090", "FlowVeil-android.apk"), rel("v0092", "FlowVeil-android.apk"), rel("v0091", "FlowVeil-android.apk"))
        assertEquals(92, UpdateLogic.pick(list, 80, arm)?.build)
    }

    @Test fun ignoresDraftPrereleaseAndNotNewer() {
        val list = listOf(rel("v0095", "FlowVeil-android.apk", draft = true), rel("v0094", "FlowVeil-android.apk", pre = true), rel("v0080", "FlowVeil-android.apk"))
        assertNull(UpdateLogic.pick(list, 80, arm))
        assertNull(UpdateLogic.pick(listOf(rel("v0070", "FlowVeil-android.apk")), 80, arm))
    }

    @Test fun needsAFittingAssetElseOlderNewerOne() {
        val list = listOf(rel("v0093", "FlowVeil-Setup.exe"), rel("v0092", "FlowVeil-android.apk"))
        assertEquals(92, UpdateLogic.pick(list, 80, arm)?.build)
        assertNull(UpdateLogic.pick(listOf(rel("v0093", "FlowVeil-Setup.exe")), 80, arm))
    }

    @Test fun prefersArm64AssetAndFindsSums() {
        val c = UpdateLogic.pick(listOf(rel("v0092", "FlowVeil-android.apk", "FlowVeil-android-arm64.apk", "SHA256SUMS.txt")), 80, arm)!!
        assertEquals("FlowVeil-android-arm64.apk", c.assetName)
        assertTrue(c.sumsUrl!!.endsWith("SHA256SUMS.txt"))
        assertEquals(listOf("FlowVeil-android.apk"), UpdateLogic.androidAssets("armeabi-v7a"))
    }

    // ---- notify policy ----

    private val day = NotifyPolicy.DAY_MS

    @Test fun notifiesOncePerVersion() {
        var s = NotifyState()
        assertTrue(NotifyPolicy.shouldNotify(s, 92, 90, 0))
        s = NotifyPolicy.afterNotified(s, 92, 0)
        assertFalse(NotifyPolicy.shouldNotify(s, 92, 90, 1 * day))
        assertFalse(NotifyPolicy.shouldNotify(s, 92, 90, 2 * day))
    }

    @Test fun remindsOnceAfterThreeDays() {
        var s = NotifyPolicy.afterNotified(NotifyState(), 92, 0)
        assertTrue(NotifyPolicy.shouldNotify(s, 92, 90, 3 * day))
        s = NotifyPolicy.afterNotified(s, 92, 3 * day)
        assertTrue(s.reminded)
        assertFalse(NotifyPolicy.shouldNotify(s, 92, 90, 30 * day))
    }

    @Test fun newerVersionNotifiesAgain() {
        val s = NotifyPolicy.afterNotified(NotifyState(), 92, 0)
        assertTrue(NotifyPolicy.shouldNotify(s, 93, 90, day))
        assertFalse(NotifyPolicy.afterNotified(s, 93, day).reminded)
    }

    @Test fun laterSnoozesThreeDaysThenOneReminder() {
        var s = NotifyPolicy.afterNotified(NotifyState(), 92, 0)
        s = NotifyPolicy.afterLater(s, day)
        assertFalse(NotifyPolicy.shouldNotify(s, 92, 90, 3 * day))
        assertFalse(NotifyPolicy.bannerVisible(s, 92, 90, 3 * day))
        assertTrue(NotifyPolicy.shouldNotify(s, 92, 90, 4 * day))
    }

    @Test fun skipHidesThatVersionOnlyAndNoDowngrade() {
        val s = NotifyPolicy.afterSkip(NotifyState(), 92)
        assertFalse(NotifyPolicy.shouldNotify(s, 92, 90, 0))
        assertFalse(NotifyPolicy.bannerVisible(s, 92, 90, 0))
        assertTrue(NotifyPolicy.shouldNotify(s, 93, 90, 0))
        assertFalse(NotifyPolicy.shouldNotify(NotifyState(), 90, 90, 0))
        assertFalse(NotifyPolicy.shouldNotify(NotifyState(), 89, 90, 0))
    }

    @Test fun stateEncodeRoundTrip() {
        val s = NotifyState(92, 5, true, 9, 91)
        assertEquals(s, NotifyState.decode(s.encode()))
        assertEquals(NotifyState(), NotifyState.decode("garbage"))
        assertEquals(NotifyState(), NotifyState.decode(null))
    }

    // ---- throttle, sums, notes ----

    @Test fun throttleAndBackoff() {
        assertTrue(CheckThrottle.mayCheck(0, 0, CheckThrottle.MIN_INTERVAL_MS))
        assertFalse(CheckThrottle.mayCheck(0, 0, CheckThrottle.MIN_INTERVAL_MS - 1))
        assertTrue(CheckThrottle.delayAfterFailures(3) > CheckThrottle.delayAfterFailures(1))
        assertEquals(CheckThrottle.MAX_BACKOFF_MS, CheckThrottle.delayAfterFailures(50))
    }

    @Test fun sha256Sums() {
        val hex = "a".repeat(64)
        val sums = Sha256Sums.parse("$hex  FlowVeil-android.apk\n${"B".repeat(64)} *FlowVeil-Setup.exe\nnot a line\n")
        assertEquals(true, Sha256Sums.verify(sums, "FlowVeil-android.apk", hex))
        assertEquals(false, Sha256Sums.verify(sums, "FlowVeil-android.apk", "c".repeat(64)))
        assertEquals(true, Sha256Sums.verify(sums, "FlowVeil-Setup.exe", "b".repeat(64)))
        assertNull(Sha256Sums.verify(sums, "other.zip", hex))
    }

    @Test fun notesAreSafePlainText() {
        val md = "## Что нового\n- **Пункт** с [ссылкой](https://evil.example/x)\n<script>alert(1)</script>\n![img](https://x/y.png)\n\n\n\nКонец"
        val plain = ReleaseNotes.plain(md)
        assertFalse(plain.contains("<"))
        assertFalse(plain.contains("evil.example"))
        assertFalse(plain.contains("**"))
        assertTrue(plain.contains("• Пункт с ссылкой"))
        assertTrue(plain.endsWith("Конец"))
        assertTrue(ReleaseNotes.plain("x".repeat(5000), 100).length <= 101)
    }
}
