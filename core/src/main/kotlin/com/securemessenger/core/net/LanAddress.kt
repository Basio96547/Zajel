package com.securemessenger.core.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * This device's own address on the local network.
 *
 * Needed for the manual/direct connection path: when mDNS can't be relied on
 * (AP isolation, a guest network, a flaky NsdManager resolve), the two sides
 * have to be able to exchange a plain "host:port" instead — which means each
 * side has to be able to *show* the user its own.
 *
 * Deliberately plain JVM (`java.net`) rather than Android's `WifiManager`: it
 * works identically on a desktop JVM, needs no extra permission, and correctly
 * reports the address of whichever interface actually carries the LAN — Wi-Fi,
 * Ethernet, or a phone acting as a hotspot.
 */
object LanAddress {

    /**
     * The best guess at "the address a peer on my network should dial", or null
     * when this device isn't on a network at all.
     *
     * Prefers a private (RFC 1918) IPv4 address on an ordinary up, non-loopback
     * interface. IPv6 is skipped on purpose: link-local v6 addresses need a
     * scope id to be dialable and are more confusing than useful to show a user
     * who is going to read this off one screen and type it into another.
     */
    fun best(): String? = candidates().firstOrNull()

    /**
     * Every plausible local IPv4 address, most-likely-correct first.
     *
     * "Private address" alone is nowhere near enough to pick with. A real
     * machine routinely carries several private addresses at once — this was
     * developed on one showing all four of:
     *
     *     192.168.1.102   Wi-Fi          ← the only one a phone can reach
     *     192.168.137.1   Windows ICS / mobile hotspot
     *     192.168.5.1     a virtual adapter
     *     10.2.0.2        an active VPN tunnel
     *
     * and every one of them is RFC 1918. Handing a peer the hotspot or the VPN
     * address produces a QR code and a "your address is…" hint that simply
     * cannot be dialled, which is far worse than showing nothing: it looks like
     * the feature worked.
     *
     * So the ordering demotes the addresses that are structurally unlikely to
     * be the LAN — VPN tunnels and gateway-shaped `.1` addresses on the
     * well-known sharing subnets — and [candidates] is public precisely because
     * this remains a heuristic. The UI shows the whole list so a user on an
     * unusual setup can pick the right one instead of being stuck with a guess.
     */
    fun candidates(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { iface ->
                // A down interface has no route; loopback only ever reaches
                // this same machine; virtual/tunnel interfaces (VPN, docker,
                // WSL) are usually the wrong answer even when they are up.
                iface.isUp && !iface.isLoopback && !iface.isVirtual
            }
            .flatMap { iface -> iface.inetAddresses.asSequence().map { iface to it } }
            .filter { (_, address) -> address is Inet4Address }
            .filterNot { (_, address) -> address.isLoopbackAddress || address.isLinkLocalAddress }
            .mapNotNull { (iface, address) -> address.hostAddress?.let { iface to it } }
            .distinctBy { (_, ip) -> ip }
            .sortedByDescending { (iface, ip) -> score(iface, ip) }
            .map { (_, ip) -> ip }
            .toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Higher is more likely to be the address a phone on the same Wi-Fi can actually reach. */
    private fun score(iface: NetworkInterface, ip: String): Int {
        var score = if (isPrivate(ip)) 10 else 0
        val name = (iface.displayName ?: iface.name ?: "").lowercase()

        // Named tunnels and virtual switches. A VPN in particular is actively
        // harmful here: it is up, it has a private address, and traffic sent to
        // it leaves the LAN entirely.
        if (VIRTUAL_HINTS.any { it in name }) score -= 20

        // Windows Internet Connection Sharing always hands the host
        // 192.168.137.1; Android/iOS tethering and many virtual switches
        // likewise put the host at x.x.x.1 of their own private subnet. A
        // device that is *serving* a subnet is rarely the one a phone joined.
        if (ip.startsWith("192.168.137.")) score -= 15
        else if (ip.endsWith(".1")) score -= 5

        // Wi-Fi and Ethernet are what a home LAN actually runs on.
        if (PHYSICAL_HINTS.any { it in name }) score += 5
        return score
    }

    private val VIRTUAL_HINTS = listOf(
        "vpn", "proton", "wireguard", "openvpn", "tap-", "tun", "tailscale", "zerotier",
        "virtual", "vethernet", "hyper-v", "vmware", "virtualbox", "docker", "wsl",
        "loopback", "bluetooth", "hotspot"
    )

    private val PHYSICAL_HINTS = listOf("wi-fi", "wifi", "wlan", "wireless", "ethernet", "لاسلكي", "شبكة")

    /**
     * The local address that shares [destination]'s /24, or null if none does.
     *
     * This is the only case where binding a socket's source address reliably
     * helps: it forces the OS to route out the interface that actually faces
     * the peer, which is what defeats a VPN's catch-all default route. Binding
     * to an unrelated address does the opposite — it makes the connection
     * impossible, which is exactly how an over-eager version of this broke
     * loopback traffic and any peer reachable on a second interface.
     *
     * /24 is an assumption, not a fact: it is right for essentially every home
     * and office network, and when it is wrong the caller simply doesn't bind
     * and lets the OS decide, so the cost of being wrong is nil.
     */
    fun matching(destination: String): String? {
        if (destination.isBlank()) return null
        val target = destination.substringBeforeLast('.', "")
        if (target.isEmpty() || target.count { it == '.' } != 2) return null
        return candidates().firstOrNull { it.substringBeforeLast('.', "") == target }
    }

    /** True for addresses that only ever reach this same machine. */
    fun isLoopback(host: String): Boolean =
        host == "localhost" || host.startsWith("127.") || host == "::1"

    /**
     * True when [host] is an address of *this* device — dialling it would mean
     * talking to ourselves.
     *
     * This is not hypothetical, and the failure it prevents is genuinely hard to
     * read. Two devices both running the same VPN are routinely assigned the
     * *same* tunnel address (a real case here: phone and PC were both 10.2.0.2).
     * If one of them ever advertises that address as its own, the other stores
     * it, dials it, and reaches its own listening socket — then receives its own
     * sealed challenge, cannot open it (it was sealed to the peer's key), and
     * reports `Seal open failed` while every send times out waiting for a bundle
     * that is never coming. Nothing in that trail points at the address.
     *
     * So an address that is ours is refused, wherever it came from — a QR hint,
     * a remembered connection, or typed by hand.
     *
     * **Callers must also compare the port**, because "same host" alone is not
     * the same thing as "ourselves". Two clones of this app on one phone (Samsung
     * Dual Messenger) legitimately reach each other through that very device's
     * address — the second one to start binds a different port precisely so they
     * can. Only host *and* port together identify our own listening socket.
     */
    fun isOwnAddress(host: String): Boolean {
        if (host.isBlank()) return false
        if (isLoopback(host)) return true
        // Deliberately every local address, not just the candidates() shortlist:
        // the VPN address is demoted out of that list, and it is exactly the one
        // this check exists to catch.
        return try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .any { it.hostAddress == host }
        } catch (e: Exception) {
            false
        }
    }

    /** RFC 1918 / carrier-grade-NAT ranges — the ones a home or office LAN actually uses. */
    private fun isPrivate(ip: String): Boolean =
        ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            (ip.startsWith("172.") && ip.substringAfter('.').substringBefore('.').toIntOrNull() in 16..31)

    /**
     * Split a user-supplied or remembered address into host and port, filling in
     * [defaultPort] when only a host was given. Returns null if [raw] can't be
     * read as an address at all, so a typo fails loudly at the call site instead
     * of producing a connection attempt to nowhere.
     */
    fun parse(raw: String, defaultPort: Int): Pair<String, Int>? {
        val trimmed = raw.trim().removePrefix("ws://").removePrefix("http://").trimEnd('/')
        if (trimmed.isEmpty()) return null
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return trimmed to defaultPort
        val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return trimmed.substring(0, colon) to port
    }
}
