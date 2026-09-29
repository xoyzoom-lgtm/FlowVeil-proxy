package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Runs protocols that Xray cannot speak (today: TUIC v5) in the bundled sing-box, one small
 * process per server. sing-box opens a local SOCKS5 inbound (127.0.0.1, random port, random
 * login) and connects onward through the TUIC outbound; the Xray config then simply treats it
 * as a SOCKS proxy, so routing, statistics, the tunnel and failover keep working unchanged.
 *
 * The binary ships as `libsingbox.so` and is started from the native library directory (the
 * app itself is excluded from its own tunnel, so the child process is too). When a build has no
 * sing-box for the device's ABI, [ensure] fails with a readable message instead of crashing.
 */
object SingboxBridge {
    private const val BINARY = "libsingbox.so"
    private const val START_TIMEOUT_MS = 4000L
    private const val RUN_DIR = "singbox"

    private class Bridge(val process: Process, val socks: ProfileItem)

    private val lock = Any()
    private val running = HashMap<String, Bridge>()
    private var testCounter = 0

    /** Bridges started inside [scoped] on this thread; they are stopped when the block ends. */
    private val scopeKeys = ThreadLocal<MutableList<String>?>()

    @Volatile
    private var straysCleaned = false

    private fun context(): Context = AngApplication.application

    private fun binary(): File = File(context().applicationInfo.nativeLibraryDir, BINARY)

    /** True when this build carries sing-box for the device. */
    fun isAvailable(): Boolean = binary().exists()

    /**
     * Runs [block] and stops every bridge that was started for it (server tests): a test must
     * not leave processes behind, and it never shares a bridge with the live connection.
     */
    fun <T> scoped(block: () -> T): T {
        val keys = mutableListOf<String>()
        scopeKeys.set(keys)
        try {
            return block()
        } finally {
            scopeKeys.remove()
            keys.forEach { stop(it) }
        }
    }

    /** Stops the bridges of the live connection (not the ones of running tests). */
    fun stopAll() {
        val victims = synchronized(lock) {
            val keys = running.keys.filterNot { it.contains(TEST_MARK) }
            keys.mapNotNull { running.remove(it) }
        }
        victims.forEach { destroy(it) }
    }

    /**
     * Starts (or reuses) the bridge for [profile] and returns the local SOCKS profile Xray
     * should use instead of it. Throws with a readable message when it cannot run.
     */
    fun ensure(profile: ProfileItem): ProfileItem {
        require(profile.configType == EConfigType.TUIC) { "SingboxBridge only serves TUIC" }
        val bin = binary()
        if (!bin.exists()) {
            throw IllegalStateException(context().getString(R.string.tuic_engine_missing))
        }
        cleanStrays()

        val scope = scopeKeys.get()
        val baseKey = identity(profile)
        val key = if (scope != null) {
            synchronized(lock) { "$baseKey$TEST_MARK${++testCounter}" }
        } else {
            baseKey
        }

        synchronized(lock) {
            running[key]?.takeIf { alive(it.process) }?.let { return it.socks }
            running.remove(key)?.let { destroy(it) }
        }

        val bridge = start(bin, profile)
        val kept = synchronized(lock) {
            val existing = running[key]?.takeIf { alive(it.process) }
            if (existing == null) running[key] = bridge
            existing
        }
        if (kept != null) {
            // Another thread started the same server meanwhile: keep its bridge, drop ours.
            destroy(bridge)
            return kept.socks
        }
        scope?.add(key)
        return bridge.socks
    }

    private const val TEST_MARK = "#test"

    private fun stop(key: String) {
        val bridge = synchronized(lock) { running.remove(key) } ?: return
        destroy(bridge)
    }

    private fun destroy(bridge: Bridge) {
        runCatching { bridge.process.destroy() }
    }

    /** `Process.isAlive` needs API 26; the app supports 24. */
    private fun alive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun identity(p: ProfileItem) = listOf(
        p.server, p.serverPort, p.username, p.password, p.sni, p.alpn, p.insecure, p.congestionControl, p.udpRelayMode,
    ).joinToString("|")

    private fun start(bin: File, profile: ProfileItem): Bridge {
        val context = context()
        val port = Utils.findRandomFreePort()
        val user = UUID.randomUUID().toString().take(8)
        val pass = UUID.randomUUID().toString().replace("-", "")
        val dir = File(context.filesDir, RUN_DIR).apply { mkdirs() }
        // The owner pid in the name lets a later run recognise leftovers of a killed process.
        val configFile = File(dir, "sb-${android.os.Process.myPid()}-$port.json")
        val output = StringBuilder()
        var process: Process? = null
        try {
            configFile.writeText(buildConfig(profile, port, user, pass))
            val started = ProcessBuilder(listOf(bin.absolutePath, "run", "-c", configFile.absolutePath))
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            process = started
            Thread {
                runCatching {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { if (output.length < 4000) output.appendLine(line) }
                        LogUtil.d(AppConfig.TAG, "sing-box: $line")
                    }
                }
            }.apply { isDaemon = true; name = "singbox-log-$port" }.start()

            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (!alive(started)) break
                if (portOpen(port)) {
                    LogUtil.i(AppConfig.TAG, "SingboxBridge: TUIC bridge for ${profile.remarks} is up on 127.0.0.1:$port")
                    val socks = ProfileItem.create(EConfigType.SOCKS).apply {
                        remarks = profile.remarks
                        server = AppConfig.LOOPBACK
                        serverPort = port.toString()
                        username = user
                        password = pass
                    }
                    return Bridge(started, socks)
                }
                Thread.sleep(40)
            }
            val log = synchronized(output) { output.toString().trim() }
            LogUtil.e(AppConfig.TAG, "SingboxBridge: sing-box did not start for ${profile.remarks}: $log")
            throw IllegalStateException(context.getString(R.string.tuic_engine_failed))
        } catch (e: Exception) {
            process?.let { runCatching { it.destroy() } }
            throw e
        } finally {
            // The file held the server password; sing-box has read it by now.
            runCatching { configFile.delete() }
        }
    }

    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(AppConfig.LOOPBACK, port), 150) }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * sing-box config for one TUIC server. The server name is resolved here (the app's own
     * traffic is direct), so sing-box needs no DNS of its own; the TLS name stays the original.
     */
    private fun buildConfig(p: ProfileItem, port: Int, user: String, pass: String): String {
        val host = p.server.orEmpty().trim().removePrefix("[").removeSuffix("]")
        val address = if (Utils.isPureIpAddress(host)) {
            host
        } else {
            InetAddress.getAllByName(host).sortedBy { it is java.net.Inet6Address }.first().hostAddress ?: host
        }
        val sni = p.sni.nullIfBlank() ?: host.takeIf { !Utils.isPureIpAddress(it) }
        val alpn = (p.alpn.nullIfBlank() ?: "h3").split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val tls = JSONObject().put("enabled", true).put("insecure", p.insecure == true).put("alpn", JSONArray(alpn))
        sni?.let { tls.put("server_name", it) }

        val outbound = JSONObject()
            .put("type", "tuic")
            .put("tag", "proxy")
            .put("server", address)
            .put("server_port", p.serverPort.orEmpty().toIntOrNull() ?: 443)
            .put("uuid", p.username.orEmpty())
            .put("password", p.password.orEmpty())
            .put("congestion_control", p.congestionControl.nullIfBlank() ?: "bbr")
            .put("udp_relay_mode", p.udpRelayMode.nullIfBlank() ?: "native")
            .put("zero_rtt_handshake", false)
            .put("heartbeat", "10s")
            .put("tls", tls)

        val inbound = JSONObject()
            .put("type", "socks")
            .put("tag", "in")
            .put("listen", AppConfig.LOOPBACK)
            .put("listen_port", port)
            .put("users", JSONArray().put(JSONObject().put("username", user).put("password", pass)))

        return JSONObject()
            .put("log", JSONObject().put("level", "warn").put("timestamp", false))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
            .put("route", JSONObject().put("final", "proxy"))
            .toString()
    }

    /**
     * Kills sing-box processes left behind by an app process that was killed. A process is a
     * leftover only when the app process named in its config file (see [start]) is gone, so the
     * bridges of the other app processes are never touched.
     */
    private fun cleanStrays() {
        if (straysCleaned) return
        straysCleaned = true
        runCatching {
            val binPath = binary().absolutePath
            File("/proc").listFiles()?.forEach { entry ->
                val pid = entry.name.toIntOrNull() ?: return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                val cmd = runCatching { File(entry, "cmdline").readText().split('\u0000') }.getOrNull() ?: return@forEach
                if (cmd.firstOrNull() != binPath) return@forEach
                val owner = Regex("""sb-(\d+)-\d+\.json""").find(cmd.lastOrNull { it.isNotEmpty() }.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (owner == null || !File("/proc/$owner").exists()) {
                    LogUtil.w(AppConfig.TAG, "SingboxBridge: stopping a leftover sing-box (pid $pid)")
                    android.os.Process.killProcess(pid)
                }
            }
        }
    }
}
