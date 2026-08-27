/**
 * Username directory + introduction relay.
 *
 * This is the one service in the whole system that deliberately holds a
 * persistent, identity-keyed record — see the module doc in relay/src/index.ts
 * for why that file goes out of its way not to. Two things live here, and
 * both exist only because a username has to be *found* by someone who isn't
 * already a contact, which the blind relay's design makes structurally
 * impossible on purpose:
 *
 *   claim(username, identityKey, signingKey)   -- reserve a public handle
 *   lookup(username) -> identityKey, signingKey -- resolve one
 *   introductions.deposit(recipientIdentityKey, blob)
 *   introductions.fetch(identityKey, proof) -> blob[]
 *
 * WHAT THIS SERVICE LEARNS, stated as plainly as relay/'s equivalent section:
 * every username ever claimed, permanently, and which public keys it belongs
 * to — that's what a directory *is*, there's no designing it away. What it
 * does NOT learn: message content (introductions carry a sealed, signed inner
 * payload — see core/.../net/Envelopes.kt's TYPE_INTRO_REQUEST), which
 * conversation-level userId belongs to which claim (that travels only
 * peer-to-peer inside the sealed payload, never through this service), or
 * anything at all about already-paired conversations, which never touch this
 * service. Observability is deliberately ON here (unlike relay/), because
 * this service's whole job is running a public namespace and hiding
 * operational problems would help nobody — but nothing in this file logs
 * *who looked up whom*, and it must stay that way; see the lookup handler.
 *
 * TRUST MODEL FOR A NEW INTRODUCTION. Accepting a request here means trusting
 * that the identityKey/signingKey pair this service hands back for a
 * username is the real one — a compromised or coerced operator could swap in
 * a substitute key for one target and intercept that person's *new*
 * introductions. It cannot touch a conversation that already exists: the
 * receiving client pins the identity key it first saw (TOFU) and raises a
 * KEY_CHANGED warning on any later mismatch (SecureRepository.kt), the same
 * protection QR pairing has always relied on. Key transparency (so a
 * substitution would be publicly, cryptographically detectable) is real
 * future work, not implemented here — v1 asks you to trust the operator for
 * first contact only, the same way you'd trust a phone book.
 */

import {
	introMailboxId,
	claimSigningPayload,
	introFetchSigningPayload,
	verifyDetached,
	fromHex,
} from "./crypto.js";

export interface Env {
	DB: D1Database;
	CLAIM_LIMITER: RateLimit;
	QUERY_LIMITER: RateLimit;
}

const USERNAME_RE = /^[a-z0-9_]{3,20}$/;
const HEX32_RE = /^[0-9a-f]{64}$/; // 32-byte key, hex
const HEX_SIG_RE = /^[0-9a-f]{128}$/; // 64-byte ed25519 signature, hex
const HEX_NONCE_RE = /^[0-9a-f]{32,64}$/; // 16-32 byte nonce, hex

/** How far a claim/fetch timestamp may drift from the server's clock before it's refused as stale (or suspiciously far in the future). */
const CLAIM_TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000;
const FETCH_TIMESTAMP_TOLERANCE_MS = 60 * 1000;

/** Largest a sealed introduction payload may be — generous for a handful of keys, a secret and a name, nowhere near a message. */
const MAX_INTRO_BLOB_BYTES = 8 * 1024;

/** Most undelivered introductions one mailbox may hold. Beyond this the oldest are dropped, same idiom as relay/'s per-mailbox cap. */
const MAX_INTRO_BLOBS_PER_MAILBOX = 64;

/**
 * Introductions live much longer than relay/'s 48h blobs: this is a lower-
 * frequency, higher-consequence event (someone trying to reach you for the
 * first time) that a person might reasonably not check for weeks.
 */
const INTRO_TTL_MS = 30 * 24 * 60 * 60 * 1000;

function noContent(): Response {
	return new Response(null, { status: 204 });
}

/** Error responses only. Unlike relay/'s deliberately uniform equivalent, a specific reason here is the feature, not a leak — see handleClaim. */
function reject(status: number, reason?: string): Response {
	return reason ? Response.json({ reason }, { status }) : new Response(null, { status });
}

function isHex32(value: unknown): value is string {
	return typeof value === "string" && HEX32_RE.test(value);
}

async function rateLimited(limiter: RateLimit, request: Request): Promise<boolean> {
	const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
	const { success } = await limiter.limit({ key: ip });
	return !success;
}

async function readJson(request: Request): Promise<unknown | null> {
	try {
		return await request.json();
	} catch {
		return null;
	}
}

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const url = new URL(request.url);
		const now = Date.now();

		// No index page, no CORS — same posture as relay/, nothing here is meant
		// to be called from a browser.
		if (url.pathname === "/claim" && request.method === "POST") {
			if (await rateLimited(env.CLAIM_LIMITER, request)) return reject(429);
			return handleClaim(request, env, now);
		}

		if (url.pathname.startsWith("/u/") && request.method === "GET") {
			if (await rateLimited(env.QUERY_LIMITER, request)) return reject(429);
			return handleLookup(url.pathname.slice("/u/".length), env);
		}

		if (url.pathname === "/introductions/deposit" && request.method === "POST") {
			if (await rateLimited(env.QUERY_LIMITER, request)) return reject(429);
			return handleIntroDeposit(request, env, now);
		}

		if (url.pathname === "/introductions/fetch" && request.method === "POST") {
			if (await rateLimited(env.QUERY_LIMITER, request)) return reject(429);
			return handleIntroFetch(request, env, now);
		}

		return reject(404);
	},

	/**
	 * Sweep whatever has outlived its own expiry. relay/ does this with a
	 * Durable Object alarm; D1 has no alarms, so the cron trigger in
	 * wrangler.jsonc is this service's equivalent — the schedule differs, the
	 * guarantee does not.
	 *
	 * It is not an optimization. Both read paths already refuse expired rows,
	 * so without this the data would simply be invisible to clients while
	 * staying perfectly readable to anyone who can read the database: an
	 * introduction addressed to someone who never opens the app again would
	 * sit in the operator's D1 permanently, naming a recipient mailbox and
	 * the minute it was deposited. relay/'s alarm exists so that "an
	 * abandoned mailbox leaves no residue to subpoena later" is true there;
	 * INTRO_TTL_MS is an empty promise here until something enforces it.
	 */
	async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
		const now = Date.now();
		await env.DB.batch([
			env.DB.prepare("DELETE FROM introductions WHERE exp <= ?").bind(now),
			env.DB.prepare("DELETE FROM intro_fetch_nonces WHERE exp <= ?").bind(now),
		]);
	},
} satisfies ExportedHandler<Env>;

/**
 * Reserve a username. First claim wins — no transfer, no dispute flow. One
 * registered username per identity: a device that loses its keys loses its
 * username the same way it loses everything else about that identity.
 */
async function handleClaim(request: Request, env: Env, now: number): Promise<Response> {
	const body = await readJson(request);
	if (!body || typeof body !== "object") return reject(400);
	const { username, identityPublicKey, signingPublicKey, timestamp, signature } = body as Record<string, unknown>;

	if (typeof username !== "string" || !USERNAME_RE.test(username)) return reject(400);
	if (!isHex32(identityPublicKey) || !isHex32(signingPublicKey)) return reject(400);
	if (typeof signature !== "string" || !HEX_SIG_RE.test(signature)) return reject(400);
	if (typeof timestamp !== "number" || Math.abs(now - timestamp) > CLAIM_TIMESTAMP_TOLERANCE_MS) return reject(400);

	const identityBytes = fromHex(identityPublicKey);
	const signingBytes = fromHex(signingPublicKey);
	const sigBytes = fromHex(signature);
	if (!identityBytes || !signingBytes || !sigBytes) return reject(400);

	const payload = claimSigningPayload(username, identityBytes, signingBytes, timestamp);
	if (!verifyDetached(payload, sigBytes, signingBytes)) return reject(400);

	// Idempotent re-claim: this exact identity already owns this exact
	// username (e.g. a retried request after a dropped response).
	const existing = await env.DB.prepare(
		"SELECT username FROM usernames WHERE identity_public_key = ?"
	).bind(identityPublicKey).first<{ username: string }>();
	if (existing) {
		return existing.username === username ? noContent() : reject(409, "already_registered");
	}

	try {
		await env.DB.prepare(
			"INSERT INTO usernames (username, identity_public_key, signing_public_key, claimed_at) VALUES (?, ?, ?, ?)"
		).bind(username, identityPublicKey, signingPublicKey, now).run();
		return noContent();
	} catch (error) {
		const message = error instanceof Error ? error.message : String(error);
		if (message.includes("UNIQUE constraint failed")) {
			return reject(409, message.includes("usernames.username") ? "taken" : "already_registered");
		}
		throw error;
	}
}

/**
 * Resolve a username to the keys a client needs to build an introduction.
 * Deliberately logs nothing about the caller beyond what Cloudflare's normal
 * request handling already captures (IP/timing, per the module doc) — no
 * "who looked up whom" table exists anywhere in this file, and none should
 * be added.
 */
async function handleLookup(username: string, env: Env): Promise<Response> {
	if (!USERNAME_RE.test(username)) return reject(400);
	const row = await env.DB.prepare(
		"SELECT username, identity_public_key, signing_public_key FROM usernames WHERE username = ?"
	).bind(username).first<{ username: string; identity_public_key: string; signing_public_key: string }>();
	if (!row) return reject(404);
	return Response.json({
		username: row.username,
		identityPublicKey: row.identity_public_key,
		signingPublicKey: row.signing_public_key,
	});
}

/**
 * Store one sealed, signed introduction for later collection. The mailbox id
 * is always derived here from the caller-supplied recipient key, never taken
 * from the caller as an id — same "don't trust a client-supplied route"
 * posture as relay/'s deposit, applied to a mailbox that (unlike relay/'s)
 * is meant to be publicly derivable.
 */
async function handleIntroDeposit(request: Request, env: Env, now: number): Promise<Response> {
	const body = await readJson(request);
	if (!body || typeof body !== "object") return reject(400);
	const { recipientIdentityPublicKey, blob } = body as Record<string, unknown>;

	if (!isHex32(recipientIdentityPublicKey)) return reject(400);
	if (typeof blob !== "string" || blob.length === 0 || blob.length > MAX_INTRO_BLOB_BYTES) return reject(400);

	const recipient = await env.DB.prepare(
		"SELECT 1 FROM usernames WHERE identity_public_key = ?"
	).bind(recipientIdentityPublicKey).first();
	if (!recipient) return reject(404);

	const mailbox = introMailboxId(fromHex(recipientIdentityPublicKey)!);
	const exp = now + INTRO_TTL_MS;

	await env.DB.batch([
		env.DB.prepare("INSERT INTO introductions (mailbox, body, exp) VALUES (?, ?, ?)").bind(mailbox, blob, exp),
		env.DB.prepare(
			`DELETE FROM introductions WHERE mailbox = ?1 AND rowid NOT IN (
				SELECT rowid FROM introductions WHERE mailbox = ?1 ORDER BY rowid DESC LIMIT ?2
			)`
		).bind(mailbox, MAX_INTRO_BLOBS_PER_MAILBOX),
	]);

	return noContent();
}

/**
 * Hand over and forget every introduction waiting for this identity —
 * destructive read, exactly mirroring relay/'s `collect`. Requires proof of
 * key possession because, unlike a relay mailbox id, this one is computable
 * by anyone who can look the owner up: knowing the id is not authorization
 * to read it, only a live signature is.
 */
async function handleIntroFetch(request: Request, env: Env, now: number): Promise<Response> {
	const body = await readJson(request);
	if (!body || typeof body !== "object") return reject(400);
	const { identityPublicKey, nonce, timestamp, signature } = body as Record<string, unknown>;

	if (!isHex32(identityPublicKey)) return reject(400);
	if (typeof nonce !== "string" || !HEX_NONCE_RE.test(nonce)) return reject(400);
	if (typeof signature !== "string" || !HEX_SIG_RE.test(signature)) return reject(400);
	if (typeof timestamp !== "number" || Math.abs(now - timestamp) > FETCH_TIMESTAMP_TOLERANCE_MS) return reject(400);

	const row = await env.DB.prepare(
		"SELECT signing_public_key FROM usernames WHERE identity_public_key = ?"
	).bind(identityPublicKey).first<{ signing_public_key: string }>();
	if (!row) return reject(404);

	const signingBytes = fromHex(row.signing_public_key);
	const nonceBytes = fromHex(nonce);
	const sigBytes = fromHex(signature);
	if (!signingBytes || !nonceBytes || !sigBytes) return reject(400);

	const payload = introFetchSigningPayload(nonceBytes, timestamp);
	if (!verifyDetached(payload, sigBytes, signingBytes)) return reject(400);

	// Spend the nonce, and only now that the signature has already passed —
	// an unverified caller must not be able to burn nonces it guessed.
	//
	// This insert is what actually makes the proof one-shot, and without it
	// the nonce would be decoration: a signature is replayable bytes, so
	// until its timestamp went stale anyone who captured one could send it
	// again and take whatever arrived in the mailbox in between. First
	// insert wins; a repeat hits the primary key and is refused with the
	// same bare 400 as every other failure on this path.
	try {
		await env.DB.prepare("INSERT INTO intro_fetch_nonces (nonce, exp) VALUES (?, ?)")
			.bind(nonce, timestamp + FETCH_TIMESTAMP_TOLERANCE_MS)
			.run();
	} catch (error) {
		const message = error instanceof Error ? error.message : String(error);
		if (message.includes("UNIQUE constraint failed")) return reject(400);
		throw error;
	}

	const mailbox = introMailboxId(fromHex(identityPublicKey)!);

	const { results } = await env.DB.prepare(
		"SELECT rowid, body FROM introductions WHERE mailbox = ? AND exp > ? ORDER BY rowid ASC"
	).bind(mailbox, now).all<{ rowid: number; body: string }>();

	if (results.length > 0) {
		await env.DB.prepare("DELETE FROM introductions WHERE mailbox = ?").bind(mailbox).run();
	}

	return Response.json({ items: results.map((r) => ({ b: r.body })) }, { headers: { "cache-control": "no-store" } });
}
