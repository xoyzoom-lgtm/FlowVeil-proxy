package com.v2ray.ang.net

/** What the direct probes saw for one host: a plain TCP connect to port 443 and, separately, a real HTTP answer. */
data class HostProbe(val host: String, val tcp: Boolean, val http: Boolean)

enum class LinkDiagnosis {
    /** Nothing wrong found. */
    OK,
    /** Only domestic hosts answer: a mobile whitelist (full or partial). */
    WHITELIST,
    /** The open internet works, only our server does not. */
    SERVER_DOWN,
    NO_NETWORK,
    /** The probes disagree: do not guess, try the quick path first and only then the ordinary failover. */
    UNSURE,
}

data class DiagnosisResult(val diagnosis: LinkDiagnosis, val why: String)

/**
 * Tells a restricted network from a dead server without trusting one host. Foreign hosts come from different
 * networks and CDNs (Google, Cloudflare, Microsoft, Apple, Mozilla) and the decision is by majority, so a single
 * reachable (or blocked) address cannot flip it. TCP connects are counted apart from HTTP answers.
 *
 *  - nothing answers at all: no network;
 *  - most foreign hosts answer over HTTP: the open internet works, the server is the problem;
 *  - no foreign host answers but a domestic one does: a whitelist (also when TCP connects and only the HTTP/TLS part is dropped);
 *  - exactly one foreign host answers while the switch is on and a domestic one answers: a partial whitelist;
 *  - anything else is contradictory: UNSURE.
 */
object NetDiagnosis {
    fun diagnose(domestic: List<HostProbe>, foreign: List<HostProbe>, partialWhitelistRule: Boolean): DiagnosisResult {
        val domesticOk = domestic.any { it.http || it.tcp }
        val n = foreign.size
        val http = foreign.count { it.http }
        val tcp = foreign.count { it.tcp }
        val tag = "domestic-ok=$domesticOk foreign-http=$http/$n foreign-tcp=$tcp/$n"
        return when {
            !domesticOk && http == 0 && tcp == 0 -> DiagnosisResult(LinkDiagnosis.NO_NETWORK, tag)
            n > 0 && http * 2 > n -> DiagnosisResult(LinkDiagnosis.SERVER_DOWN, tag)
            !domesticOk -> DiagnosisResult(LinkDiagnosis.UNSURE, "$tag (foreign partly up, domestic down)")
            http == 0 -> DiagnosisResult(LinkDiagnosis.WHITELIST, if (tcp * 2 > n) "$tag (TCP connects, HTTP dropped)" else tag)
            http == 1 && partialWhitelistRule -> DiagnosisResult(LinkDiagnosis.WHITELIST, "$tag (partial whitelist)")
            else -> DiagnosisResult(LinkDiagnosis.UNSURE, "$tag (contradictory)")
        }
    }
}
