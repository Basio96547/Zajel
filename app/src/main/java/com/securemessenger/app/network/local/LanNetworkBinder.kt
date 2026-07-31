package com.securemessenger.app.network.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

private const val TAG = "LanNetworkBinder"

/**
 * Keeps local peer traffic on the Wi-Fi/Ethernet interface even while a VPN is up.
 *
 * A VPN client normally installs a default route covering everything, so a
 * connection to a peer at 192.168.1.102 — one hop away on the same Wi-Fi — gets
 * pulled into the tunnel and dropped. The two devices are as good as
 * disconnected despite sharing a network, and nothing about it is visible to
 * the user: discovery may even still work (the system's mDNS runs per-interface)
 * while every actual socket fails.
 *
 * Android's answer is [Network.getSocketFactory]: a socket created from a
 * specific [Network] is bound to that network's routing table, VPN or no VPN.
 * That is what this class supplies.
 *
 * **Why this is the right traffic to exempt, and the relay is not.** Local peer
 * traffic never leaves the LAN, and it is already Double-Ratchet encrypted and
 * sealed — a VPN adds nothing to it but a way to fail. The blind relay is the
 * exact opposite: it is the one party that unavoidably observes an IP address,
 * so its traffic deliberately keeps using the default network and stays inside
 * the tunnel. Splitting them this way makes the VPN protect precisely what it
 * can protect, instead of breaking the part it cannot help.
 *
 * If the device has no non-VPN network at all, [socketFactory] falls back to the
 * platform default rather than failing — better a socket that might work than
 * one that certainly won't.
 *
 * **Limitation worth stating:** this cannot bypass an "always-on VPN" configured
 * with *Block connections without VPN* (lockdown). That setting exists exactly
 * to forbid what this does, and the OS enforces it below the app. On such a
 * device local P2P only works if the VPN itself is set to allow LAN traffic.
 */
class LanNetworkBinder(context: Context) {

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The most recently seen non-VPN, LAN-capable network. */
    @Volatile
    private var lanNetwork: Network? = null

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        stop()
        // TRANSPORT_VPN is deliberately absent from the request and explicitly
        // rejected below: `NetworkRequest` alone will happily hand back the VPN
        // network, since it also carries WIFI capabilities on many devices.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isLan(network)) {
                    lanNetwork = network
                    Log.d(TAG, "bound local traffic to $network")
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) lanNetwork = network
            }

            override fun onLost(network: Network) {
                if (lanNetwork == network) {
                    lanNetwork = null
                    Log.d(TAG, "lost the LAN network")
                }
            }
        }
        callback = cb
        try {
            // registerNetworkCallback (not requestNetwork): we only want to
            // *observe* which network carries the LAN, never to ask the system
            // to bring one up or to change the process-wide default.
            connectivity.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed — local sockets will use the default network", e)
            callback = null
        }
        // Seed immediately: callbacks only fire on change, so without this the
        // first connection attempt after start() would use the default network.
        lanNetwork = currentLanNetwork()
    }

    fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        lanNetwork = null
    }

    private fun isLan(network: Network): Boolean {
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    @Suppress("DEPRECATION")
    private fun currentLanNetwork(): Network? = try {
        connectivity.allNetworks.firstOrNull { isLan(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * A socket factory that resolves the LAN network **per socket**, not once at
     * construction. Wi-Fi comes and goes, and a VPN reconnecting replaces the
     * default network underneath us; a factory captured at build time would keep
     * handing out sockets bound to a network that no longer exists.
     */
    val socketFactory: SocketFactory = object : SocketFactory() {
        private fun delegate(): SocketFactory =
            lanNetwork?.socketFactory ?: getDefault()

        override fun createSocket(): Socket = delegate().createSocket()

        override fun createSocket(host: String?, port: Int): Socket =
            delegate().createSocket(host, port)

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            delegate().createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            delegate().createSocket(host, port)

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            delegate().createSocket(address, port, localAddress, localPort)
    }
}
