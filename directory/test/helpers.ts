/**
 * What a real client does before it ever talks to this service: mint an
 * identity, sign a claim, sign a fetch proof. Deliberately built out of
 * `src/crypto.ts` itself rather than a second copy of the byte layouts — a
 * test that re-implemented `claimSigningPayload` would keep passing after the
 * real one drifted, which is exactly the failure `DirectoryProtocolTest` on
 * the Kotlin side exists to catch. Cross-platform agreement is that test's
 * job; this suite's job is the server's behaviour.
 */
import { SELF, env } from "cloudflare:test";
import { ed25519 } from "@noble/curves/ed25519.js";
import { claimSigningPayload, introFetchSigningPayload, toHex, fromHex } from "../src/crypto.js";
import schemaSql from "../schema.sql?raw";

/**
 * Drops whatever is there and applies the real `schema.sql`, so a column
 * renamed there without updating a query fails here instead of on first
 * deploy.
 *
 * The drop is not decoration: one D1 instance is shared across the file, and
 * a username claimed by one test is still claimed in the next — half this
 * suite failed with a 409 during setup before this dropped tables rather than
 * only creating them. Reading the table list back out of sqlite_master rather
 * than naming the three tables keeps it correct when a fourth is added.
 *
 * Comments are stripped before splitting on `;` because SQLite's own
 * statement splitter isn't available here: D1's `exec` wants one statement per
 * line, and schema.sql is neither one-per-line nor comment-free. Safe only
 * because nothing in it puts `--` or `;` inside a string literal, which is
 * worth knowing if that ever changes.
 */
export async function resetSchema(): Promise<void> {
	const existing = await env.DB.prepare(
		"SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' AND name NOT LIKE 'd1_%'"
	).all<{ name: string }>();
	if (existing.results.length > 0) {
		await env.DB.batch(existing.results.map((row) => env.DB.prepare(`DROP TABLE IF EXISTS "${row.name}"`)));
	}

	const statements = schemaSql
		.replace(/--[^\n]*/g, "")
		.split(";")
		.map((statement) => statement.trim())
		.filter((statement) => statement.length > 0);
	await env.DB.batch(statements.map((statement) => env.DB.prepare(statement)));
}

export interface TestIdentity {
	/** X25519 in production; this service only ever compares and hashes it, so random bytes are honest here. */
	identityBytes: Uint8Array;
	identityHex: string;
	signingBytes: Uint8Array;
	signingHex: string;
	signingSecret: Uint8Array;
}

export function randomBytes(length: number): Uint8Array {
	return crypto.getRandomValues(new Uint8Array(length));
}

export function newIdentity(): TestIdentity {
	// An Ed25519 secret key is 32 arbitrary bytes, so generating them directly
	// keeps this working across @noble/curves' renames of its own helper.
	const signingSecret = randomBytes(32);
	const signingBytes = ed25519.getPublicKey(signingSecret);
	const identityBytes = randomBytes(32);
	return {
		identityBytes,
		identityHex: toHex(identityBytes),
		signingBytes,
		signingHex: toHex(signingBytes),
		signingSecret,
	};
}

/** A claim body signed the way a client signs it. `signAs` exists so a test can sign with the wrong key on purpose. */
export function signedClaim(
	identity: TestIdentity,
	username: string,
	options: { timestamp?: number; signAs?: TestIdentity; signedUsername?: string } = {}
): Record<string, unknown> {
	const timestamp = options.timestamp ?? Date.now();
	const signer = options.signAs ?? identity;
	const payload = claimSigningPayload(
		options.signedUsername ?? username,
		identity.identityBytes,
		identity.signingBytes,
		timestamp
	);
	return {
		username,
		identityPublicKey: identity.identityHex,
		signingPublicKey: identity.signingHex,
		timestamp,
		signature: toHex(ed25519.sign(payload, signer.signingSecret)),
	};
}

/** An `/introductions/fetch` proof. Returned as a plain object so a test can replay the exact same one. */
export function signedFetchProof(
	identity: TestIdentity,
	options: { timestamp?: number; nonce?: Uint8Array; signAs?: TestIdentity } = {}
): Record<string, unknown> {
	const timestamp = options.timestamp ?? Date.now();
	const nonce = options.nonce ?? randomBytes(16);
	const signer = options.signAs ?? identity;
	const payload = introFetchSigningPayload(nonce, timestamp);
	return {
		identityPublicKey: identity.identityHex,
		nonce: toHex(nonce),
		timestamp,
		signature: toHex(ed25519.sign(payload, signer.signingSecret)),
	};
}

/**
 * Every request gets its own source IP unless a test says otherwise.
 *
 * The rate limiters are keyed on `CF-Connecting-IP`, and a suite that let
 * every request share one address would start failing at request 31 for a
 * reason that has nothing to do with what it was testing. Passing a fixed ip
 * is how the rate-limiting test asks for the opposite.
 */
let ipCounter = 0;
export function freshIp(): string {
	ipCounter += 1;
	return `10.${(ipCounter >> 16) & 0xff}.${(ipCounter >> 8) & 0xff}.${ipCounter & 0xff}`;
}

export function post(path: string, body: unknown, ip: string = freshIp()): Promise<Response> {
	return SELF.fetch(`https://directory.test${path}`, {
		method: "POST",
		headers: { "content-type": "application/json", "CF-Connecting-IP": ip },
		body: JSON.stringify(body),
	});
}

export function get(path: string, ip: string = freshIp()): Promise<Response> {
	return SELF.fetch(`https://directory.test${path}`, {
		method: "GET",
		headers: { "CF-Connecting-IP": ip },
	});
}

/** Registers an identity under a username, asserting the happy path so callers can treat it as setup. */
export async function claim(identity: TestIdentity, username: string): Promise<void> {
	const response = await post("/claim", signedClaim(identity, username));
	if (response.status !== 204) {
		throw new Error(`setup claim of "${username}" failed with ${response.status}`);
	}
}

export async function fetchIntroductions(
	identity: TestIdentity,
	proof: Record<string, unknown> = signedFetchProof(identity)
): Promise<{ status: number; bodies: string[] }> {
	const response = await post("/introductions/fetch", proof);
	if (response.status !== 200) return { status: response.status, bodies: [] };
	const body = (await response.json()) as { items: { b: string }[] };
	return { status: response.status, bodies: body.items.map((item) => item.b) };
}

export { fromHex, toHex };
