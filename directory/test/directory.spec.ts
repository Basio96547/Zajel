/**
 * The username directory's server side, which until now was the only part of
 * this system with no test of any kind. `DirectoryProtocolTest` in :desktop
 * proves the two platforms agree on the bytes; `IntroductionRoundtripTest` in
 * :app proves a device can complete the round trip — against a service that
 * has to be deployed first. Neither one ever asked this Worker whether it
 * refuses what it claims to refuse.
 *
 * That is what is here: not that the happy path works, but that each stated
 * guarantee fails closed — an unsigned or wrongly-signed claim, a proof made
 * with someone else's key, a replayed proof, a mailbox that grew without
 * bound, a row that outlived its TTL.
 */
import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";
import { introMailboxId } from "../src/crypto.js";
import {
	resetSchema,
	claim,
	fetchIntroductions,
	get,
	newIdentity,
	post,
	randomBytes,
	signedClaim,
	signedFetchProof,
} from "./helpers";

beforeEach(resetSchema);

const FIVE_MINUTES = 5 * 60 * 1000;

describe("claiming a username", () => {
	it("accepts a signed claim and resolves it afterwards", async () => {
		const alice = newIdentity();

		const claimed = await post("/claim", signedClaim(alice, "alice"));
		expect(claimed.status).toBe(204);

		const looked_up = await get("/u/alice");
		expect(looked_up.status).toBe(200);
		expect(await looked_up.json()).toEqual({
			username: "alice",
			identityPublicKey: alice.identityHex,
			signingPublicKey: alice.signingHex,
		});
	});

	it("refuses a claim signed by a key other than the one being registered", async () => {
		const alice = newIdentity();
		const impostor = newIdentity();

		const response = await post("/claim", signedClaim(alice, "alice", { signAs: impostor }));

		expect(response.status).toBe(400);
		expect((await get("/u/alice")).status).toBe(404);
	});

	it("refuses a claim whose signature covers a different username", async () => {
		// The signature is real and the key is right; only the name it was made
		// over differs. Without the username inside the signed payload this
		// would sail through, and anyone could re-point a captured claim.
		const alice = newIdentity();

		const response = await post("/claim", signedClaim(alice, "alice", { signedUsername: "bob" }));

		expect(response.status).toBe(400);
	});

	it("refuses a timestamp outside the tolerance, in either direction", async () => {
		const stale = newIdentity();
		const ahead = newIdentity();

		const past = await post("/claim", signedClaim(stale, "stale", { timestamp: Date.now() - FIVE_MINUTES - 30_000 }));
		const future = await post("/claim", signedClaim(ahead, "ahead", { timestamp: Date.now() + FIVE_MINUTES + 30_000 }));

		expect(past.status).toBe(400);
		expect(future.status).toBe(400);
	});

	it("refuses usernames outside the published shape", async () => {
		for (const username of ["ab", "Alice", "has-dash", "has space", "a".repeat(21), ""]) {
			const response = await post("/claim", signedClaim(newIdentity(), username));
			expect({ username, status: response.status }).toEqual({ username, status: 400 });
		}
	});

	it("treats a repeat of the same claim as already done", async () => {
		// A retry after a dropped response must not look like a conflict, or a
		// client on a bad connection can never finish setup.
		const alice = newIdentity();
		await claim(alice, "alice");

		const again = await post("/claim", signedClaim(alice, "alice"));

		expect(again.status).toBe(204);
	});

	it("holds one username per identity", async () => {
		const alice = newIdentity();
		await claim(alice, "alice");

		const second = await post("/claim", signedClaim(alice, "alice2"));

		expect(second.status).toBe(409);
		expect(await second.json()).toEqual({ reason: "already_registered" });
		expect((await get("/u/alice2")).status).toBe(404);
	});

	it("gives a taken name to whoever claimed it first", async () => {
		const first = newIdentity();
		const second = newIdentity();
		await claim(first, "alice");

		const contested = await post("/claim", signedClaim(second, "alice"));

		expect(contested.status).toBe(409);
		expect(await contested.json()).toEqual({ reason: "taken" });
		const resolved = (await (await get("/u/alice")).json()) as { identityPublicKey: string };
		expect(resolved.identityPublicKey).toBe(first.identityHex);
	});

	it("answers 404 for an unclaimed name and 400 for an impossible one", async () => {
		expect((await get("/u/nobody")).status).toBe(404);
		expect((await get("/u/NOBODY")).status).toBe(400);
	});
});

describe("introductions", () => {
	it("delivers a deposited introduction once, and only to a live signature", async () => {
		const alice = newIdentity();
		await claim(alice, "alice");

		const deposited = await post("/introductions/deposit", {
			recipientIdentityPublicKey: alice.identityHex,
			blob: "sealed-introduction",
		});
		expect(deposited.status).toBe(204);

		const first = await fetchIntroductions(alice);
		expect(first.bodies).toEqual(["sealed-introduction"]);

		// Destructive read, exactly like relay/'s collect: nothing is kept for a
		// second reader.
		const second = await fetchIntroductions(alice);
		expect(second.bodies).toEqual([]);
	});

	it("refuses to hold an introduction for an identity nobody has claimed", async () => {
		const stranger = newIdentity();

		const response = await post("/introductions/deposit", {
			recipientIdentityPublicKey: stranger.identityHex,
			blob: "sealed-introduction",
		});

		expect(response.status).toBe(404);
	});

	it("refuses a proof signed with someone else's key, and leaves the mailbox untouched", async () => {
		// The whole point of the proof: this mailbox id is derived from a public
		// key, so anyone who can look Alice up can address it. Knowing the id
		// must not be enough to drain it.
		const alice = newIdentity();
		const impostor = newIdentity();
		await claim(alice, "alice");
		await post("/introductions/deposit", { recipientIdentityPublicKey: alice.identityHex, blob: "for-alice" });

		const attempt = await fetchIntroductions(alice, signedFetchProof(alice, { signAs: impostor }));
		expect(attempt.status).toBe(400);

		const owner = await fetchIntroductions(alice);
		expect(owner.bodies).toEqual(["for-alice"]);
	});

	it("refuses a proof whose timestamp has gone stale", async () => {
		const alice = newIdentity();
		await claim(alice, "alice");

		const attempt = await fetchIntroductions(alice, signedFetchProof(alice, { timestamp: Date.now() - 120_000 }));

		expect(attempt.status).toBe(400);
	});

	it("refuses a replayed proof, so a captured one cannot drain what arrives next", async () => {
		// This is the guarantee DirectoryProtocol.introFetchSigningPayload's doc
		// makes ("a captured proof can't be replayed to drain a mailbox a second
		// time") and the one a signature alone cannot keep: the same bytes verify
		// forever. Only the server refusing a nonce it has already honoured makes
		// it true, and until it did, everything below passed anyway.
		const alice = newIdentity();
		await claim(alice, "alice");
		await post("/introductions/deposit", { recipientIdentityPublicKey: alice.identityHex, blob: "first" });

		const proof = signedFetchProof(alice);
		const legitimate = await fetchIntroductions(alice, proof);
		expect(legitimate.bodies).toEqual(["first"]);

		await post("/introductions/deposit", { recipientIdentityPublicKey: alice.identityHex, blob: "second" });
		const replayed = await fetchIntroductions(alice, proof);
		expect(replayed.status).toBe(400);

		// Still there for its owner, who signs a fresh proof like a real client does.
		const owner = await fetchIntroductions(alice);
		expect(owner.bodies).toEqual(["second"]);
	});

	it("spends a nonce only after the signature checks out", async () => {
		// Otherwise an unauthenticated caller could burn a nonce it guessed and
		// pre-emptively refuse the owner's next real fetch.
		const alice = newIdentity();
		const impostor = newIdentity();
		await claim(alice, "alice");
		await post("/introductions/deposit", { recipientIdentityPublicKey: alice.identityHex, blob: "for-alice" });

		const nonce = randomBytes(16);
		const forged = signedFetchProof(alice, { nonce, signAs: impostor });
		expect((await fetchIntroductions(alice, forged)).status).toBe(400);

		const genuine = await fetchIntroductions(alice, signedFetchProof(alice, { nonce }));
		expect(genuine.bodies).toEqual(["for-alice"]);
	});

	it("refuses a blob larger than the cap", async () => {
		const alice = newIdentity();
		await claim(alice, "alice");

		const response = await post("/introductions/deposit", {
			recipientIdentityPublicKey: alice.identityHex,
			blob: "x".repeat(8 * 1024 + 1),
		});

		expect(response.status).toBe(400);
	});

	it("bounds one mailbox at 64 introductions, keeping the newest", async () => {
		const alice = newIdentity();
		await claim(alice, "alice");
		for (let i = 0; i < 70; i++) {
			await post("/introductions/deposit", {
				recipientIdentityPublicKey: alice.identityHex,
				blob: `intro-${i}`,
			});
		}

		const { bodies } = await fetchIntroductions(alice);

		expect(bodies).toHaveLength(64);
		expect(bodies[0]).toBe("intro-6");
		expect(bodies[63]).toBe("intro-69");
	});

	it("never serves an introduction that has outlived its TTL", async () => {
		const alice = newIdentity();
		await claim(alice, "alice");
		await depositExpired(alice.identityBytes, "too-old");

		const { bodies } = await fetchIntroductions(alice);

		expect(bodies).toEqual([]);
	});
});

describe("the scheduled sweep", () => {
	it("deletes what has expired and keeps what has not", async () => {
		// Both read paths already refuse expired rows, so nothing above would
		// notice if this never ran — and the rows would sit in the operator's
		// database permanently, which is the entire reason the sweep exists.
		const alice = newIdentity();
		await claim(alice, "alice");
		await depositExpired(alice.identityBytes, "too-old");
		await post("/introductions/deposit", { recipientIdentityPublicKey: alice.identityHex, blob: "still-fresh" });
		await env.DB.prepare("INSERT INTO intro_fetch_nonces (nonce, exp) VALUES (?, ?)")
			.bind("00".repeat(16), Date.now() - 1000)
			.run();
		await env.DB.prepare("INSERT INTO intro_fetch_nonces (nonce, exp) VALUES (?, ?)")
			.bind("11".repeat(16), Date.now() + 60_000)
			.run();

		await runScheduled();

		const introductions = await env.DB.prepare("SELECT body FROM introductions").all<{ body: string }>();
		expect(introductions.results.map((row) => row.body)).toEqual(["still-fresh"]);
		const nonces = await env.DB.prepare("SELECT nonce FROM intro_fetch_nonces").all<{ nonce: string }>();
		expect(nonces.results.map((row) => row.nonce)).toEqual(["11".repeat(16)]);
	});
});

describe("rate limiting", () => {
	it("cuts off a single address hammering the claim path", async () => {
		// Five per minute per IP. Each attempt is a fresh identity so nothing is
		// refused for being a duplicate — only for arriving too fast.
		const attacker = "203.0.113.9";
		const statuses: number[] = [];
		for (let i = 0; i < 8; i++) {
			const response = await post("/claim", signedClaim(newIdentity(), `squat${i}`), attacker);
			statuses.push(response.status);
		}

		expect(statuses).toContain(429);
		expect(statuses.filter((status) => status === 204).length).toBeLessThanOrEqual(5);
	});
});

/** Writes an introduction straight to D1 with an expiry already in the past — the one state no public path can produce on demand. */
async function depositExpired(identityBytes: Uint8Array, blob: string): Promise<void> {
	await env.DB.prepare("INSERT INTO introductions (mailbox, body, exp) VALUES (?, ?, ?)")
		.bind(introMailboxId(identityBytes), blob, Date.now() - 1000)
		.run();
}

/** Fires the cron handler directly: the pool can drive a fetch for us, but nothing here advances a schedule. */
async function runScheduled(): Promise<void> {
	await worker.scheduled({ scheduledTime: Date.now(), cron: "17 * * * *", noRetry() {} }, env);
}
