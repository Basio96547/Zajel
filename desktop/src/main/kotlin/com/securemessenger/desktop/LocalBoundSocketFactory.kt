package com.securemessenger.desktop

import com.securemessenger.core.net.LanAddress
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Routes local peer traffic out the interface that faces the peer, so a VPN's
 * catch-all default route can't swallow it.
 *
 * With a tunnel up, a connection to a peer at 192.168.1.101 — one hop away on
 * the same Wi-Fi — is handed to the VPN and dropped. Binding the socket's
 * *source* address to the local address on that same subnet makes the OS pick
 * the matching route instead.
 *
 * **Binding is deliberately narrow, and always optional.** An earlier version
 * bound every socket to whichever address [LanAddress.best] returned, which is
 * strictly worse than not binding at all whenever the guess doesn't face the
 * destination: it turns "might be tunnelled" into "definitely cannot connect".
 * The loopback tests caught exactly that — a source of 192.168.1.102 cannot
 * reach 127.0.0.1. So a source address is chosen only when one genuinely shares
 * the destination's subnet, and even then a failed connect is retried unbound
 * rather than reported as unreachable.
 *
 * The Android client does the equivalent through `Network.getSocketFactory()`;
 * see `LanNetworkBinder`. Both leave relay traffic on the default route on
 * purpose, since the relay is the one party that sees an IP.
 */
object LocalBoundSocketFactory : SocketFactory() {

    /** Unconnected socket — OkHttp connects it itself, so there is no destination to match against yet. */
    override fun createSocket(): Socket = Socket()

    override fun createSocket(host: String, port: Int): Socket =
        connect(InetSocketAddress(host, port), host)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        connect(InetSocketAddress(host, port), host.hostAddress ?: "")

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        Socket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        Socket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }

    private fun connect(target: InetSocketAddress, host: String): Socket {
        // Loopback never needs help and never tolerates a LAN source address.
        val source = if (LanAddress.isLoopback(host)) null else LanAddress.matching(host)
        if (source != null) {
            val bound = Socket()
            try {
                bound.bind(InetSocketAddress(InetAddress.getByName(source), 0))
                bound.connect(target)
                return bound
            } catch (e: Exception) {
                // The interface we picked can't reach it after all — close and
                // fall through rather than turning a reachable peer into an
                // unreachable one.
                runCatching { bound.close() }
            }
        }
        return Socket().apply { connect(target) }
    }
}
