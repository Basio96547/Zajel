/**
 * The two primitives this service needs, and nothing else.
 *
 * Deliberately not hand-rolled: `@noble/hashes` and `@noble/curves` are
 * pure-TS, take raw byte arrays directly (no DER/SPKI wrapping, unlike
 * Workers' native WebCrypto Ed25519 support), and are the standard choice
 * for a Workers project that needs to speak the same raw-key format the
 * Android/desktop clients already use via libsodium.
 *
 * Every function here has a matching implementation in
 * `core/src/main/kotlin/com/securemessenger/core/`. The two must produce
 * byte-identical output for the same input —
 * `core/.../net/DirectoryProtocolTest.kt` hardcodes vectors generated from
 * this exact file (see that test's own doc comment for how) and asserts the
 * Kotlin side against them. Change a byte layout here without regenerating
 * and updating those vectors, and claiming/lookup silently stop verifying
 * across platforms.
 */

import { blake2b } from "@noble/hashes/blake2b.js";
import { ed25519 } from "@noble/curves/ed25519.js";

export function toHex(bytes: Uint8Array): string {
	return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function fromHex(hex: string): Uint8Array | null {
	if (hex.length % 2 !== 0 || !/^[0-9a-f]*$/i.test(hex)) return null;
	const out = new Uint8Array(hex.length / 2);
	for (let i = 0; i < out.length; i++) {
		out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
	}
	return out;
}

/**
 * The one-shot, non-rotating mailbox a stranger deposits an introduction
 * request into — mirrors `MailboxToken.introMailboxId` in `:core` exactly
 * (unkeyed BLAKE2b, 16-byte output, same domain-separation prefix). See that
 * function's doc for why this is the single deliberate exception to "no
 * mailbox id is ever guessable."
 */
export function introMailboxId(identityPublicKey: Uint8Array): string {
	const prefix = new TextEncoder().encode("intro|v1|");
	const data = new Uint8Array(prefix.length + identityPublicKey.length);
	data.set(prefix, 0);
	data.set(identityPublicKey, prefix.length);
	return toHex(blake2b(data, { dkLen: 16 }));
}

/** Big-endian 8-byte encoding of a millisecond timestamp — must match `DirectoryProtocol`'s Kotlin side bit-for-bit. */
export function timestampToBytes(millis: number): Uint8Array {
	const out = new Uint8Array(8);
	let big = BigInt(Math.trunc(millis));
	for (let i = 7; i >= 0; i--) {
		out[i] = Number(big & 0xffn);
		big >>= 8n;
	}
	return out;
}

function concat(...parts: Uint8Array[]): Uint8Array {
	const total = parts.reduce((n, p) => n + p.length, 0);
	const out = new Uint8Array(total);
	let offset = 0;
	for (const part of parts) {
		out.set(part, offset);
		offset += part.length;
	}
	return out;
}

/** Mirrors `DirectoryProtocol.claimSigningPayload` in `:core`. */
export function claimSigningPayload(
	username: string,
	identityPublicKey: Uint8Array,
	signingPublicKey: Uint8Array,
	timestampMillis: number
): Uint8Array {
	return concat(
		new TextEncoder().encode("sm-directory-claim|v1|"),
		new TextEncoder().encode(username),
		new Uint8Array([0]),
		identityPublicKey,
		signingPublicKey,
		timestampToBytes(timestampMillis)
	);
}

/**
 * Mirrors `DirectoryProtocol.introFetchSigningPayload` in `:core`.
 *
 * There is deliberately no equivalent here of `DirectoryProtocol`'s
 * `introSigningPayload` (the signature *inside* an intro_request/
 * intro_accept). That signature is verified only by the receiving client
 * after it unseals the payload with `crypto_box_seal` — this service never
 * sees the plaintext, so it has nothing to verify and must not pretend to.
 */
export function introFetchSigningPayload(nonce: Uint8Array, timestampMillis: number): Uint8Array {
	return concat(
		new TextEncoder().encode("sm-directory-intro-fetch|v1|"),
		nonce,
		timestampToBytes(timestampMillis)
	);
}

export function verifyDetached(message: Uint8Array, signature: Uint8Array, publicKey: Uint8Array): boolean {
	if (signature.length !== 64 || publicKey.length !== 32) return false;
	try {
		return ed25519.verify(signature, message, publicKey);
	} catch {
		return false;
	}
}
