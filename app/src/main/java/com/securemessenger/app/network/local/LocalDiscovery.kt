package com.securemessenger.app.network.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "LocalDiscovery"
private const val SERVICE_TYPE = "_securemessenger._tcp."

/** An app instance found on the local network. [token] is the short opaque identity hash advertised in the service name. */
data class DiscoveredPeer(val host: String, val port: Int, val token: String)

/**
 * Finds other instances of this app on the same local network (WiFi) via
 * mDNS/DNS-SD (Android's Network Service Discovery) — the only "directory"
 * that exists now that there's no external server. A device is only
 * reachable while it's also revealed and advertising, exactly mirroring how
 * the old relay only delivered live to a currently-connected recipient.
 */
class LocalDiscovery(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    /**
     * The name the system actually registered us under. Not always the name we
     * asked for: on a name collision Android silently appends a suffix, and the
     * real name only comes back in [NsdManager.RegistrationListener.onServiceRegistered].
     * Discovery reports our own service back to us, so knowing this is what lets
     * us skip resolving ourselves.
     */
    @Volatile
    private var registeredServiceName: String? = null

    /** Advertise this device's local server. [token] should be a short, non-reversible hash of the identity key — enough to recognize a known contact, not enough to leak who we are to strangers on the LAN. */
    fun register(port: Int, token: String) {
        unregister()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "sm-$token"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "registered as ${info.serviceName} on port $port")
                registeredServiceName = info.serviceName
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredServiceName = null
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "registerService threw", e)
        }
    }

    fun unregister() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {
            }
        }
        registrationListener = null
    }

    /**
     * The live set of currently-discovered peers, keyed by service name —
     * updates as devices come and go. Cancel the collecting coroutine (or
     * let it be scoped to a lifecycle) to stop browsing.
     */
    fun discoverPeers(): Flow<List<DiscoveredPeer>> = callbackFlow {
        val peers = linkedMapOf<String, DiscoveredPeer>()

        // NsdManager resolves ONE service at a time. Firing a resolve while
        // another is in flight fails the new one outright with
        // FAILURE_ALREADY_ACTIVE (3) — and `onServiceFound` fires once per peer
        // in quick succession, so calling resolve directly from it meant that
        // as soon as more than one instance of this app was on the network,
        // some of them simply never resolved and were never reachable. The
        // symptom is indistinguishable from "the contact is offline."
        //
        // So resolves are queued and run strictly one after another, each one
        // starting only when the previous has reported back either way.
        val pending = ArrayDeque<NsdServiceInfo>()
        val resolving = java.util.concurrent.atomic.AtomicBoolean(false)

        // Declared before use so the resolve callbacks can drive the next one.
        lateinit var pump: () -> Unit

        /** Hand the queue back and immediately start whatever is next in line. */
        fun finish() {
            resolving.set(false)
            pump()
        }

        pump = {
            // Claim the single in-flight slot; whoever loses the race just
            // returns, because the winner will pump again when it finishes.
            if (resolving.compareAndSet(false, true)) {
                val next = synchronized(pending) { pending.removeFirstOrNull() }
                if (next == null) {
                    resolving.set(false)
                } else {
                    try {
                        // A listener instance may never be reused across
                        // resolves (the NsdManager contract), so each gets a
                        // fresh one.
                        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                Log.e(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                                finish()
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress
                                if (host != null) {
                                    val token = info.serviceName.removePrefix("sm-")
                                    peers[info.serviceName] = DiscoveredPeer(host, info.port, token)
                                    trySend(peers.values.toList())
                                }
                                finish()
                            }
                        })
                    } catch (e: Exception) {
                        // Must still release the slot, or one throw would wedge
                        // discovery permanently with a queue that never drains.
                        Log.e(TAG, "resolveService threw", e)
                        finish()
                    }
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("securemessenger")) return
                // Our own advertised service comes back through discovery too;
                // resolving it costs a queue slot and can never produce a peer.
                if (service.serviceName == registeredServiceName) return
                synchronized(pending) {
                    if (pending.none { it.serviceName == service.serviceName }) pending.addLast(service)
                }
                pump()
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                peers.remove(service.serviceName)
                synchronized(pending) { pending.removeAll { it.serviceName == service.serviceName } }
                trySend(peers.values.toList())
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "discovery failed to start: $errorCode")
                try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices threw", e)
        }

        awaitClose {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {
            }
        }
    }
}
