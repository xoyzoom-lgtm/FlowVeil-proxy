package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.NetInfoCache
import com.v2ray.ang.net.NetCandidate
import com.v2ray.ang.net.NetType
import com.v2ray.ang.net.NetworkPicker
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet6Address

/**
 * Which real network the phone sits on, seen THROUGH our own tunnel.
 *
 * With the tunnel up the default network is the tunnel itself, so this looks at every network
 * that is not a VPN and has internet, and picks the one the system routes over (see
 * [NetworkPicker]). It follows changes with a plain network callback (not the default-network
 * one). No location or phone-state permission is needed and neither a Wi-Fi name nor an operator
 * is ever read: only the transport type and whether the link has a global IPv6 address.
 * Everything here runs in the core process; the UI reads the result from [NetInfoCache].
 */
object PhysicalNetwork {
    private const val DEBOUNCE_MS = 2_500L

    data class Snapshot(
        val type: NetType,
        val network: Network?,
        val others: List<NetType>,
        val hasIpv6: Boolean,
        /** The primary network is behind a login page: it has no real internet yet. */
        val captive: Boolean,
        /** When [type] last changed (not when the handle did). */
        val since: Long,
    ) {
        val key: Long get() = network?.networkHandle ?: 0L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    var snapshot = Snapshot(NetType.NONE, null, emptyList(), false, false, 0L)
        private set

    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var debounceJob: Job? = null
    private var listener: ((old: Snapshot, new: Snapshot) -> Unit)? = null

    /**
     * Starts following the physical network. [onChanged] gets both snapshots whenever the type or
     * the underlying network handle changed (debounced, on a background thread).
     */
    fun start(context: Context, onChanged: (old: Snapshot, new: Snapshot) -> Unit) {
        synchronized(lock) {
            listener = onChanged
            if (callback != null) return
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            manager = cm
            recompute(notify = false)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = schedule()
                override fun onLost(network: Network) = schedule()
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = schedule()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = schedule()
            }
            try {
                // A plain request never lists VPN networks (NOT_VPN is one of its default
                // capabilities), so this follows the real networks, not our own tunnel.
                cm.registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    cb
                )
                callback = cb
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "PhysicalNetwork: cannot watch networks", e)
            }
        }
    }

    /** Stops following (unregisters the callback, cancels the pending debounce) and forgets the state. */
    fun stop() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = null
            val cb = callback
            callback = null
            listener = null
            if (cb != null) runCatching { manager?.unregisterNetworkCallback(cb) }
            manager = null
            snapshot = Snapshot(NetType.NONE, null, emptyList(), false, false, 0L)
        }
    }

    /** Looks again right now, without the debounce; returns the (possibly unchanged) snapshot. */
    fun refreshNow(): Snapshot = recompute(notify = false)

    private fun schedule() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                recompute(notify = true)
            }
        }
    }

    /** Every real (non-tunnel) network the system knows, as candidates, plus their handles. */
    @Suppress("DEPRECATION")
    private fun candidatesOf(cm: ConnectivityManager): Pair<List<NetCandidate>, Map<Long, Network>> {
        val candidates = mutableListOf<NetCandidate>()
        val handles = HashMap<Long, Network>()
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val type = NetworkPicker.classify(
                internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            ) ?: continue
            val handle = network.networkHandle
            handles[handle] = network
            candidates += NetCandidate(
                key = handle,
                type = type,
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                hasIpv6 = hasGlobalIpv6(cm, network),
                captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            )
        }
        return candidates to handles
    }

    /** One-off look for screens outside the core process (the diagnosis): no callbacks, nothing stored or published. */
    fun lookNow(context: Context): Snapshot? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val (candidates, handles) = candidatesOf(cm)
        val selection = NetworkPicker.pick(candidates)
        val primary = selection.primary
        return Snapshot(
            type = primary?.type ?: NetType.NONE,
            network = primary?.let { handles[it.key] },
            others = selection.others.map { it.type }.distinct(),
            hasIpv6 = primary?.hasIpv6 == true,
            captive = primary?.captive == true,
            since = 0L,
        )
    }

    private fun recompute(notify: Boolean): Snapshot {
        val cm = manager ?: return snapshot
        val (candidates, handles) = candidatesOf(cm)
        val selection = NetworkPicker.pick(candidates)
        val primary = selection.primary
        val (old, updated) = synchronized(lock) {
            val previous = snapshot
            val type = primary?.type ?: NetType.NONE
            val next = Snapshot(
                type = type,
                network = primary?.let { handles[it.key] },
                others = selection.others.map { it.type }.distinct(),
                hasIpv6 = primary?.hasIpv6 == true,
                captive = primary?.captive == true,
                since = if (type != previous.type) System.currentTimeMillis() else previous.since,
            )
            snapshot = next
            previous to next
        }
        NetInfoCache.writeType(updated.type, updated.others)
        val changed = old.type != updated.type || old.key != updated.key
        if (changed) {
            LogUtil.i(AppConfig.TAG, "PhysicalNetwork: ${old.type.id} -> ${updated.type.id}" + (if (updated.others.isNotEmpty()) " (also ${updated.others.joinToString { it.id }})" else ""))
            if (notify) listener?.invoke(old, updated)
        }
        return updated
    }

    private fun hasGlobalIpv6(cm: ConnectivityManager, network: Network): Boolean = runCatching {
        cm.getLinkProperties(network)?.linkAddresses.orEmpty().any { la ->
            (la.address as? Inet6Address)?.let {
                !it.isLinkLocalAddress && !it.isLoopbackAddress && !it.isSiteLocalAddress && !it.isAnyLocalAddress
            } == true
        }
    }.getOrDefault(false)
}
