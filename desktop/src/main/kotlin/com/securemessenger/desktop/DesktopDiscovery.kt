package com.securemessenger.desktop

import com.securemessenger.core.net.LanAddress
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** A peer found on the local network. Mirrors the Android client's `DiscoveredPeer`. */
data class DesktopPeer(val host: String, val port: Int, val token: String)

/**
 * mDNS/DNS-SD for the desktop client, the counterpart to Android's NsdManager.
 *
 * The service type, the `sm-<token>` name and the meaning of that token are
 * copied exactly from the Android side — a phone has to recognise this client as
 * just another instance of the app, not as something new that needs special
 * handling. Get any of the three wrong and the two simply never see each other.
 *
 * jmDNS is bound to one interface address rather than the wildcard on purpose: a
 * desktop routinely has several (Ethernet, Wi-Fi, VPN, WSL, Docker bridges), and
 * letting the library pick can leave it advertising on a virtual interface no
 * phone can reach — an unusually confusing failure, because everything looks
 * like it is working.
 */
class DesktopDiscovery {

    companion object {
        const val SERVICE_TYPE = "_securemessenger._tcp.local."
    }

    private var jmdns: JmDNS? = null
    private var registered: ServiceInfo? = null

    /** host -> peer, keyed by service name, refreshed as devices come and go. */
    private val peers = java.util.concurrent.ConcurrentHashMap<String, DesktopPeer>()

    @Volatile
    private var onPeersChanged: ((List<DesktopPeer>) -> Unit)? = null

    /** The interface address jmDNS bound to — also what we advertise as our direct address. */
    @Volatile
    var boundAddress: String? = null
        private set

    fun start(port: Int, token: String, onPeers: (List<DesktopPeer>) -> Unit) {
        onPeersChanged = onPeers
        stop()
        val address = LanAddress.best()?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        val instance = JmDNS.create(address, "securemessenger-desktop")
        boundAddress = address?.hostAddress
        jmdns = instance

        instance.addServiceListener(SERVICE_TYPE, object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // `serviceAdded` carries only the name; the address and port
                // arrive with the resolve, which jmDNS performs asynchronously
                // and reports through serviceResolved.
                instance.requestServiceInfo(event.type, event.name, 1000)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                peers.remove(event.name)
                onPeersChanged?.invoke(peers.values.toList())
            }

            override fun serviceResolved(event: ServiceEvent) {
                // Discovery reports our own advertisement back to us; resolving
                // ourselves would add a peer that is this very process.
                if (event.name == registered?.name) return
                val info = event.info ?: return
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                peers[event.name] = DesktopPeer(host, info.port, event.name.removePrefix("sm-"))
                onPeersChanged?.invoke(peers.values.toList())
            }
        })

        register(port, token)
    }

    /** (Re-)advertise under [token]. Called again whenever the rotating token rolls over. */
    fun register(port: Int, token: String) {
        val instance = jmdns ?: return
        registered?.let { runCatching { instance.unregisterService(it) } }
        val info = ServiceInfo.create(SERVICE_TYPE, "sm-$token", port, "")
        runCatching { instance.registerService(info) }
            .onSuccess { registered = info }
    }

    fun stop() {
        jmdns?.let { instance ->
            runCatching { registered?.let { instance.unregisterService(it) } }
            runCatching { instance.close() }
        }
        registered = null
        jmdns = null
        peers.clear()
    }
}
