package com.v2ray.ang.handler

import android.content.Context
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.PhysicalNetwork
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.net.DiagInputs
import com.v2ray.ang.net.DiagResult
import com.v2ray.ang.net.DiagStepId
import com.v2ray.ang.net.Diagnosis
import com.v2ray.ang.net.NetType
import com.v2ray.ang.net.ProbeStep
import com.v2ray.ang.net.ReportMask
import com.v2ray.ang.net.SubIssue
import com.v2ray.ang.net.SubscriptionHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * "Why does it not work?": makes the checks one after another (so the screen can show each result as it
 * comes) and hands the numbers to the pure [Diagnosis.diagnose]. It reuses what the app already has:
 * [PhysicalNetwork] for the real network, [NetProbe] for the requests (real network or through the
 * server), [LocalProxy] for the real local port, [SubscriptionErrors]/[SubscriptionHealth] for the
 * subscription, [BatteryOptimization] for the power exemption. Nothing here is sent anywhere.
 */
object DiagnosticsRunner {
    /** What the last run saw, kept for the copied report. */
    @Volatile
    var lastInputs: DiagInputs? = null
        private set

    /** Steps done so far and the result computed from what is known at that moment. */
    fun interface Progress {
        fun onStep(done: DiagStepId, partial: DiagResult)
    }

    suspend fun run(context: Context, running: Boolean, progress: Progress?): DiagResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        var inputs = DiagInputs(net = NetType.NONE, running = running)
        fun step(id: DiagStepId, update: DiagInputs.() -> DiagInputs) {
            inputs = inputs.update()
            progress?.onStep(id, Diagnosis.diagnose(inputs))
        }

        // 1. the real network, and whether the answer is the real one (a login page answers 200, not 204)
        val snapshot = PhysicalNetwork.lookNow(app)
        val net = snapshot?.type ?: NetType.NONE
        var captive = snapshot?.captive == true
        var skewMs: Long? = null
        var dnsOk: Boolean? = null
        if (net != NetType.NONE) {
            val client = NetProbe.physical(snapshot?.network)
            client.use {
                val answer = it.get(NetProbe.GSTATIC_204)
                val step = answer.step
                if ((step.kind == ProbeStep.Kind.OK && step.code != 204) || (step.kind == ProbeStep.Kind.BAD_STATUS && step.code in 200..399)) captive = true
                answer.serverDateMs?.let { server -> skewMs = System.currentTimeMillis() - server }
            }
            dnsOk = runCatching {
                val network = snapshot?.network
                if (network != null) network.getAllByName("www.gstatic.com").isNotEmpty() else java.net.InetAddress.getAllByName("www.gstatic.com").isNotEmpty()
            }.getOrDefault(false)
        }
        step(DiagStepId.NETWORK) { copy(net = net, captive = captive) }
        step(DiagStepId.TIME) { copy(clockSkewMs = skewMs) }
        step(DiagStepId.DNS) { copy(dnsOk = dnsOk) }

        // 2. the subscription of the selected server
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        val subId = profile?.subscriptionId.orEmpty()
        val sub = if (subId.isNotBlank()) MmkvManager.decodeSubscription(subId) else null
        val issue = if (sub == null) SubIssue.NONE else {
            val names = MmkvManager.decodeServerList(subId).mapNotNull { MmkvManager.decodeServerConfig(it)?.remarks }
            val byNames = SubscriptionHealth.byServerNames(names)
            val byInfo = SubscriptionHealth.byInfo(sub.expireAt, sub.trafficUsed, sub.trafficTotal, System.currentTimeMillis())
            SubscriptionHealth.combine(byNames, byInfo, SubscriptionErrors.issue(subId))
        }
        step(DiagStepId.SUBSCRIPTION) { copy(sub = issue) }

        // 3. the server itself (TCP only: UDP-based protocols cannot be judged by a connect)
        val tcpBased = profile?.configType in setOf(EConfigType.VMESS, EConfigType.VLESS, EConfigType.TROJAN, EConfigType.SHADOWSOCKS, EConfigType.SOCKS, EConfigType.HTTP)
        val host = profile?.server
        val port = profile?.serverPort?.toIntOrNull()
        val reachable: Boolean? = if (tcpBased && !host.isNullOrBlank() && port != null && net != NetType.NONE) {
            SpeedtestManager.socketConnectTime(host, port, 3000) >= 0
        } else null
        step(DiagStepId.SERVER) { copy(hasServer = profile != null, serverReachable = reachable) }

        // 4. end to end through the running core (gstatic must answer exactly 204)
        var e2e: ProbeStep? = null
        if (running && guid != null) {
            val route = LocalProxy.resolve(guid)
            if (route != null) e2e = NetProbe.tunnel(route).use { it.get(NetProbe.GSTATIC_204).step }
        }
        step(DiagStepId.END_TO_END) { copy(endToEnd = e2e) }

        // 5. is the real network itself restricted (domestic answers, foreign does not)? Only needed when the tunnel does not carry
        var domestic: Boolean? = null
        var foreign: Boolean? = null
        val tunnelOk = e2e != null && e2e.kind == ProbeStep.Kind.OK && e2e.code == 204
        if (!tunnelOk && net != NetType.NONE && !captive) {
            NetProbe.physical(snapshot?.network).use { client ->
                coroutineScope {
                    val d = async { NetProbe.DOMESTIC_SITES.map { url -> async { client.reachable(url) } }.awaitAll().any { it } }
                    val f = async { NetProbe.FOREIGN_SITES.map { url -> async { client.reachable(url) } }.awaitAll().any { it } }
                    domestic = d.await()
                    foreign = f.await()
                }
            }
        }
        step(DiagStepId.RESTRICTION) { copy(domesticDirect = domestic, foreignDirect = foreign) }

        // 6. battery
        step(DiagStepId.BATTERY) { copy(batteryIgnored = BatteryOptimization.isIgnored(app)) }

        val result = Diagnosis.diagnose(inputs)
        lastInputs = inputs
        BypassLog.add("diag: cause=${result.cause.id} warnings=${result.warnings.joinToString(",") { it.id }} net=${net.id} sub=${issue.name} e2e=${e2e?.kind}/${e2e?.code}")
        result
    }

    /** The copied report: build, system, network type, core versions, cause codes, the tail of the log. Masked, no identifiers. */
    fun report(result: DiagResult): String {
        val i = lastInputs
        val text = buildString {
            appendLine("FlowVeil build ${BuildConfig.HUPP_BUILD}")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ABI ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
            appendLine("Xray: ${runCatching { CoreNativeManager.getLibVersion() }.getOrDefault("?")}; sing-box: ${if (com.v2ray.ang.core.SingboxBridge.isAvailable()) "yes" else "no"}")
            if (i != null) appendLine("Network: ${i.net.id}${if (i.captive) ", login page" else ""}; clock skew: ${i.clockSkewMs?.let { "${it / 1000}s" } ?: "?"}")
            appendLine("Verdict: ${result.cause.id}")
            MmkvManager.decodeSettingsString(AppConfig.CACHE_LAST_START_ERROR)?.takeIf { it.isNotBlank() }?.let { appendLine("Last refused start: $it") }
            if (result.warnings.isNotEmpty()) appendLine("Warnings: ${result.warnings.joinToString { it.id }}")
            appendLine("Steps:")
            result.steps.forEach { appendLine("  ${it.id.name}: ${it.status.name}${it.cause?.let { c -> " (${c.id})" } ?: ""}") }
            val tail = BypassLog.read().lines().filter { it.isNotBlank() }.takeLast(30)
            if (tail.isNotEmpty()) {
                appendLine("Log:")
                tail.forEach { appendLine("  $it") }
            }
        }
        return ReportMask.apply(text)
    }
}
