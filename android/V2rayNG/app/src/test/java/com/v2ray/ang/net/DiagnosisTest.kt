package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosisTest {
    private val ok204 = ProbeStep.OK_204
    private fun base(block: DiagInputs.() -> DiagInputs = { this }) =
        DiagInputs(net = NetType.WIFI, running = true, endToEnd = ok204, serverReachable = true, domesticDirect = true, foreignDirect = true, batteryIgnored = true).block()

    private fun cause(i: DiagInputs) = Diagnosis.diagnose(i).cause

    @Test fun allGood() = assertEquals(DiagCause.OK, cause(base()))

    @Test fun noNetworkFirst() = assertEquals(DiagCause.NO_NETWORK, cause(base { copy(net = NetType.NONE, sub = SubIssue.EXPIRED) }))

    @Test fun captivePortal() = assertEquals(DiagCause.CAPTIVE_PORTAL, cause(base { copy(captive = true) }))

    @Test fun wrongTime() {
        assertEquals(DiagCause.WRONG_TIME, cause(base { copy(clockSkewMs = 10 * 60_000L) }))
        assertEquals(DiagCause.WRONG_TIME, cause(base { copy(clockSkewMs = -2 * 3_600_000L) }))
        assertEquals(DiagCause.OK, cause(base { copy(clockSkewMs = 60_000L) }))
        assertEquals(DiagCause.OK, cause(base { copy(clockSkewMs = null) }))
    }

    @Test fun dnsBeforeSubscription() = assertEquals(DiagCause.DNS_FAILED, cause(base { copy(dnsOk = false, sub = SubIssue.EXPIRED) }))

    @Test fun subscriptionStatesWinOverServer() {
        assertEquals(DiagCause.SUB_EXPIRED, cause(base { copy(sub = SubIssue.EXPIRED, serverReachable = false) }))
        assertEquals(DiagCause.SUB_DEVICE_LIMIT, cause(base { copy(sub = SubIssue.DEVICE_LIMIT) }))
    }

    @Test fun noServerAndNotConnectedAndDown() {
        assertEquals(DiagCause.NO_SERVER, cause(base { copy(hasServer = false) }))
        assertEquals(DiagCause.NOT_CONNECTED, cause(base { copy(running = false, endToEnd = null) }))
        assertEquals(DiagCause.SERVER_DOWN, cause(base { copy(serverReachable = false) }))
        assertEquals(DiagCause.OK, cause(base { copy(serverReachable = null) }))
    }

    @Test fun runningButNotPassing() {
        val bad = ProbeStep(ProbeStep.Kind.TIMEOUT)
        assertEquals(DiagCause.SERVER_NOT_PASSING, cause(base { copy(endToEnd = bad) }))
        // 200 instead of 204 is a rewritten answer, still not a working server
        assertEquals(DiagCause.SERVER_NOT_PASSING, cause(base { copy(endToEnd = ProbeStep.ok(200)) }))
    }

    @Test fun mobileRestrictedNeedsDomesticUpForeignDown() {
        val bad = ProbeStep(ProbeStep.Kind.TIMEOUT)
        assertEquals(DiagCause.MOBILE_RESTRICTED, cause(base { copy(net = NetType.CELLULAR, endToEnd = bad, foreignDirect = false) }))
        assertEquals(DiagCause.SERVER_NOT_PASSING, cause(base { copy(endToEnd = bad, domesticDirect = false, foreignDirect = false) }))
    }

    @Test fun batteryIsAdvisoryOnly() {
        val r = Diagnosis.diagnose(base { copy(batteryIgnored = false) })
        assertEquals(DiagCause.OK, r.cause)
        assertTrue(DiagCause.BATTERY_RESTRICTED in r.warnings)
        assertEquals(StepStatus.WARN, r.steps.first { it.id == DiagStepId.BATTERY }.status)
    }

    @Test fun restrictionWarnsEvenWhenAnotherCauseWins() {
        val r = Diagnosis.diagnose(base { copy(running = false, endToEnd = null, foreignDirect = false) })
        assertEquals(DiagCause.NOT_CONNECTED, r.cause)
        assertTrue(DiagCause.MOBILE_RESTRICTED in r.warnings)
    }

    @Test fun everyStepPresentOnce() {
        val ids = Diagnosis.diagnose(base()).steps.map { it.id }
        assertEquals(DiagStepId.entries.toList(), ids)
    }

    // ---- subscription health ----

    @Test fun markersInServerNames() {
        assertEquals(SubIssue.DEVICE_LIMIT, SubscriptionHealth.byServerNames(listOf("⚠️ Превышен лимит устройств")))
        assertEquals(SubIssue.EXPIRED, SubscriptionHealth.byServerNames(listOf("Subscription expired")))
        assertEquals(SubIssue.NONE, SubscriptionHealth.byServerNames(listOf("Германия", "Нидерланды", "Финляндия", "Швеция", "Expired-ru test")))
        assertEquals(SubIssue.NONE, SubscriptionHealth.byServerNames(emptyList()))
        // a long real list with a notice among the servers is not a verdict
        assertEquals(SubIssue.NONE, SubscriptionHealth.byServerNames(List(20) { "Server $it" } + "device limit"))
    }

    @Test fun infoNumbers() {
        val now = 2_000_000_000_000L
        assertEquals(SubIssue.EXPIRED, SubscriptionHealth.byInfo(now - 1, null, null, now))
        assertEquals(SubIssue.NONE, SubscriptionHealth.byInfo(now + 1, 10, 100, now))
        assertEquals(SubIssue.TRAFFIC_OVER, SubscriptionHealth.byInfo(null, 100, 100, now))
        assertEquals(SubIssue.NONE, SubscriptionHealth.byInfo(null, 100, null, now))
        assertEquals(SubIssue.NONE, SubscriptionHealth.byInfo(0, 0, 0, now))
    }

    @Test fun httpStatuses() {
        assertEquals(SubIssue.LINK_UNKNOWN, SubscriptionHealth.byHttpStatus(404))
        assertEquals(SubIssue.ACCESS_DENIED, SubscriptionHealth.byHttpStatus(403))
        assertEquals(SubIssue.RATE_LIMITED, SubscriptionHealth.byHttpStatus(429))
        assertEquals(SubIssue.PROVIDER_DOWN, SubscriptionHealth.byHttpStatus(502))
        assertEquals(SubIssue.UNREACHABLE, SubscriptionHealth.byHttpStatus(418))
    }

    @Test fun bodies() {
        assertEquals(SubIssue.HAPP_CRYPT, SubscriptionHealth.byBody("happ://crypt3/abc"))
        assertEquals(SubIssue.WEB_PAGE, SubscriptionHealth.byBody("  <!doctype html>"))
        assertEquals(SubIssue.NO_SERVERS, SubscriptionHealth.byBody("garbage"))
    }

    @Test fun combinePrefersWordsOverNumbersOverErrors() {
        assertEquals(SubIssue.DEVICE_LIMIT, SubscriptionHealth.combine(SubIssue.DEVICE_LIMIT, SubIssue.EXPIRED, SubIssue.LINK_UNKNOWN))
        assertEquals(SubIssue.EXPIRED, SubscriptionHealth.combine(SubIssue.NONE, SubIssue.EXPIRED, SubIssue.LINK_UNKNOWN))
        assertEquals(SubIssue.LINK_UNKNOWN, SubscriptionHealth.combine(SubIssue.NONE, SubIssue.NONE, SubIssue.LINK_UNKNOWN))
    }

    // ---- battery guide, masking ----

    @Test fun makers() {
        assertEquals(BatteryGuide.Maker.XIAOMI, BatteryGuide.maker("Xiaomi", "POCO"))
        assertEquals(BatteryGuide.Maker.HUAWEI, BatteryGuide.maker("HUAWEI", "HONOR"))
        assertEquals(BatteryGuide.Maker.OPPO, BatteryGuide.maker("realme", null))
        assertEquals(BatteryGuide.Maker.OTHER, BatteryGuide.maker("Google", "google"))
    }

    @Test fun maskHidesLinksKeysIpsAndIds() {
        val text = "sub https://p.example/sub/SECRET?t=1 vless://11111111-2222-3333-4444-555555555555@h.example:443 " +
            "pair http://192.168.1.5:5123/p/SID#t=TOK&k=KEYKEY&v=1 ip 8.8.8.8 v6 2001:db8:0:0:0:0:0:1 token=abc ssid: HomeWifi id 11111111-2222-3333-4444-555555555555"
        val masked = ReportMask.apply(text)
        for (secret in listOf("SECRET", "p.example", "TOK", "KEYKEY", "192.168.1.5", "8.8.8.8", "2001:db8", "abc", "HomeWifi", "555555555555")) {
            assertFalse("leaked $secret in: $masked", masked.contains(secret))
        }
        assertTrue(masked.contains("vless://***"))
    }

    @Test fun maskLeavesPlainText() = assertEquals("Android 14, Pixel 7, wifi", ReportMask.apply("Android 14, Pixel 7, wifi"))

    @Test fun httpDate() {
        assertEquals(1_700_000_000_000L, HttpDate.parse("Tue, 14 Nov 2023 22:13:20 GMT"))
        assertEquals(null, HttpDate.parse("yesterday"))
        assertEquals(null, HttpDate.parse(null))
    }
}
