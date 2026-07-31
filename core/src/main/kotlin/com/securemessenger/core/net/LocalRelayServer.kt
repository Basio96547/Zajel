package com.securemessenger.core.net

import com.securemessenger.core.Platform
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "LocalRelayServer"

/** Hard cap on simultaneously-open sockets — a personal messenger never legitimately needs more than a handful at once; this just bounds how much a misbehaving/malicious device on the same LAN can pile onto us. */
private const val MAX_CONCURRENT_CONNECTIONS = 20

/**
 * Largest text frame we will accept, in chars. Comfortably fits a max-size
 * (4 MB) media message after its two base64 layers, while stopping a malicious
 * peer on the LAN from forcing huge allocations / GC pressure with oversized
 * frames.
 */
private const val MAX_FRAME_CHARS = 12 * 1024 * 1024

/**
 * This device's own relay — there is no external server at all. Every phone
 * runs one of these while the messenger is revealed, accepting direct
 * WebSocket connections from other instances of the app on the same network.
 *
 * Deliberately does no connection-level authentication: a forged sender
 * can't produce an envelope that decrypts under a pinned contact's Double
 * Ratchet session, so legitimacy is already enforced by the crypto layer
 * itself, exactly like the old relay's job was routing only — never trust.
 */
class LocalRelayServer(port: Int) : NanoWSD(port) {

    /** Called for every text frame from any connected peer, with a way to reply on that same socket. */
    var onEnvelope: ((text: String, reply: (String) -> Unit) -> Unit)? = null

    private val openConnections = AtomicInteger(0)

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return object : WebSocket(handshake) {
            override fun onOpen() {
                val current = openConnections.incrementAndGet()
                if (current > MAX_CONCURRENT_CONNECTIONS) {
                    Platform.log.warn(TAG, "rejecting connection — $current concurrent sockets exceeds cap")
                    try {
                        close(WebSocketFrame.CloseCode.PolicyViolation, "too many connections", false)
                    } catch (e: IOException) {
                        Platform.log.error(TAG, "failed to close over-limit connection", e)
                    }
                    return
                }
            }

            override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                openConnections.decrementAndGet()
            }

            override fun onMessage(message: WebSocketFrame) {
                val text = message.textPayload ?: return
                if (text.length > MAX_FRAME_CHARS) {
                    Platform.log.warn(TAG, "rejecting oversized frame (${text.length} chars) and closing socket")
                    try {
                        close(WebSocketFrame.CloseCode.PolicyViolation, "frame too large", false)
                    } catch (_: IOException) {
                    }
                    return
                }
                try {
                    onEnvelope?.invoke(text) { reply -> send(reply) }
                } catch (e: Exception) {
                    Platform.log.error(TAG, "onEnvelope handler failed", e)
                }
            }

            override fun onPong(pong: WebSocketFrame) {}

            override fun onException(exception: IOException) {
                Platform.log.error(TAG, "peer socket error", exception)
            }
        }
    }
}
