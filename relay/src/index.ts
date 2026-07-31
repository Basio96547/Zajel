/**
 * Blind mailbox relay.
 *
 * This service is a dead-drop, not a messaging server. It has no accounts, no
 * registration, no usernames, no public keys, and no notion of a conversation.
 * Its entire vocabulary is:
 *
 *   deposit(mailboxId, opaqueBytes)   fetch(mailboxId[]) -> opaqueBytes[]
 *
 * A mailbox id is 16 random-looking bytes that both ends of one conversation
 * derive from a secret they exchanged in person by photographing a QR code (see
 * MailboxToken.kt in the Android app). The id rolls over every hour and each
 * direction of a conversation uses a different one, so from here the traffic of
 * a single long-running conversation looks like a stream of unrelated ids
 * appearing and disappearing.
 *
 * The bytes themselves are encrypted twice over before they arrive: the payload
 * by the Double Ratchet, and the envelope around it by a key derived from the
 * same pair secret. This service cannot read either layer, and deliberately
 * holds nothing that would let it try.
 *
 * WHAT THIS DESIGN STILL EXPOSES, stated plainly because encryption does not
 * fix it: the network layer sees the IP address of whoever connects and the
 * time they connect. Nothing in this file can change that — only routing over
 * Tor or a VPN would. Everything else has been designed away.
 *
 * SHARDING. Mailboxes are spread across SHARD_COUNT Durable Objects by the
 * first byte of the id. That is deliberately the *only* thing the routing
 * depends on: any scheme that grouped a conversation's mailboxes into the same
 * shard would hand this service exactly the linkage the rotating ids exist to
 * hide. The cost is that a client fetching several mailboxes touches several
 * shards, which the Worker fans out to in parallel.
 */

import { DurableObject } from "cloudflare:workers";

export interface Env {
	MAILBOX: DurableObjectNamespace<BlindMailbox>;
}

/**
 * How many Durable Objects the mailbox space is spread over. Capped at 32 so a
 * single fetch can never fan out past the Workers subrequest limit, even when
 * every id in it lands on a different shard.
 */
const SHARD_COUNT = 32;

/** How long an undelivered blob survives before it is swept. */
const TTL_MS = 48 * 60 * 60 * 1000;

/** Largest single deposit. Clients split anything bigger into several same-sized blobs at their own encrypted layer, so this is not a message size limit. */
const MAX_BLOB_BYTES = 200 * 1024;

/** Most undelivered blobs one mailbox may hold. Beyond this the oldest are dropped — bounds what a flooder can make us store. */
const MAX_BLOBS_PER_MAILBOX = 256;

/** Most mailbox ids one fetch may ask about. Bounds the fan-out of a single request. */
const MAX_FETCH_IDS = 48;

/** Most blobs one deposit request may carry. */
const MAX_DEPOSIT_ITEMS = 32;

const MAILBOX_ID_RE = /^[0-9a-f]{32}$/;

interface BlobRow extends Record<string, SqlStorageValue> {
	rowid: number;
	body: string;
}

export class BlindMailbox extends DurableObject<Env> {
	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		ctx.blockConcurrencyWhile(async () => {
			this.ctx.storage.sql.exec(`
				CREATE TABLE IF NOT EXISTS blobs (
					rowid   INTEGER PRIMARY KEY AUTOINCREMENT,
					mailbox TEXT NOT NULL,
					body    TEXT NOT NULL,
					exp     INTEGER NOT NULL
				)
			`);
			this.ctx.storage.sql.exec(`CREATE INDEX IF NOT EXISTS idx_blobs_mailbox ON blobs(mailbox)`);
			this.ctx.storage.sql.exec(`CREATE INDEX IF NOT EXISTS idx_blobs_exp ON blobs(exp)`);
		});
	}

	/**
	 * Store blobs for later collection. Returns nothing at all — not a count,
	 * not an id. A depositor learns only that the request did not fail, which
	 * is all it needs and all it should get.
	 */
	async deposit(items: { m: string; b: string }[], now: number): Promise<void> {
		const exp = now + TTL_MS;
		for (const item of items) {
			this.ctx.storage.sql.exec(
				"INSERT INTO blobs (mailbox, body, exp) VALUES (?, ?, ?)",
				item.m,
				item.b,
				exp
			);
			// Trim this mailbox back to the cap, oldest first. Scoped to the one
			// mailbox that just grew so a flooder can only ever evict its own
			// blobs, never someone else's.
			this.ctx.storage.sql.exec(
				`DELETE FROM blobs WHERE mailbox = ?1 AND rowid NOT IN (
					SELECT rowid FROM blobs WHERE mailbox = ?1 ORDER BY rowid DESC LIMIT ?2
				)`,
				item.m,
				MAX_BLOBS_PER_MAILBOX
			);
		}
		await this.ensureSweepScheduled(now);
	}

	/**
	 * Hand over and immediately forget every blob waiting in these mailboxes.
	 * Delivery is destructive: nothing is retained for a second reader, and
	 * there is no record that a read happened.
	 */
	async collect(ids: string[], now: number): Promise<{ m: string; b: string }[]> {
		const out: { m: string; b: string }[] = [];
		for (const id of ids) {
			const rows = this.ctx.storage.sql
				.exec<BlobRow>(
					"SELECT rowid, body FROM blobs WHERE mailbox = ? AND exp > ? ORDER BY rowid ASC",
					id,
					now
				)
				.toArray();
			if (rows.length === 0) continue;
			for (const row of rows) out.push({ m: id, b: row.body });
			this.ctx.storage.sql.exec("DELETE FROM blobs WHERE mailbox = ?", id);
		}
		return out;
	}

	private async ensureSweepScheduled(now: number): Promise<void> {
		// One alarm per DO — only set it when nothing is already pending, so a
		// busy shard doesn't keep pushing its own sweep into the future.
		const existing = await this.ctx.storage.getAlarm();
		if (existing === null) {
			await this.ctx.storage.setAlarm(now + 60 * 60 * 1000);
		}
	}

	/**
	 * Sweep expired blobs. Anything nobody collected within TTL_MS is gone —
	 * an abandoned mailbox leaves no residue to subpoena later.
	 */
	async alarm(): Promise<void> {
		const now = Date.now();
		this.ctx.storage.sql.exec("DELETE FROM blobs WHERE exp <= ?", now);
		const remaining = this.ctx.storage.sql
			.exec<{ n: number }>("SELECT COUNT(*) AS n FROM blobs")
			.one().n;
		// Keep sweeping only while something is actually stored, so an idle
		// shard costs nothing.
		if (remaining > 0) await this.ctx.storage.setAlarm(now + 60 * 60 * 1000);
	}
}

function shardFor(mailboxId: string): string {
	return `s${parseInt(mailboxId.slice(0, 2), 16) % SHARD_COUNT}`;
}

function groupByShard<T>(items: T[], idOf: (item: T) => string): Map<string, T[]> {
	const groups = new Map<string, T[]>();
	for (const item of items) {
		const shard = shardFor(idOf(item));
		const bucket = groups.get(shard);
		if (bucket) bucket.push(item);
		else groups.set(shard, [item]);
	}
	return groups;
}

function isValidId(value: unknown): value is string {
	return typeof value === "string" && MAILBOX_ID_RE.test(value);
}

/** Uniform, information-free response. Every failure looks the same from outside. */
function reject(): Response {
	return new Response(null, { status: 400 });
}

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const url = new URL(request.url);

		// Deliberately no index page, no health endpoint that reveals counts,
		// and no CORS — nothing here is meant to be reachable from a browser.
		if (request.method !== "POST") return new Response(null, { status: 405 });

		const now = Date.now();

		if (url.pathname === "/d") {
			let body: unknown;
			try {
				body = await request.json();
			} catch {
				return reject();
			}
			const rawItems = (body as { items?: unknown })?.items;
			if (!Array.isArray(rawItems) || rawItems.length === 0 || rawItems.length > MAX_DEPOSIT_ITEMS) {
				return reject();
			}
			const items: { m: string; b: string }[] = [];
			for (const raw of rawItems) {
				const m = (raw as { m?: unknown })?.m;
				const b = (raw as { b?: unknown })?.b;
				if (!isValidId(m)) return reject();
				if (typeof b !== "string" || b.length === 0 || b.length > MAX_BLOB_BYTES) return reject();
				items.push({ m, b });
			}
			await Promise.all(
				[...groupByShard(items, (i) => i.m)].map(([shard, group]) =>
					env.MAILBOX.getByName(shard).deposit(group, now)
				)
			);
			// No body: a depositor is told only that nothing went wrong.
			return new Response(null, { status: 204 });
		}

		if (url.pathname === "/f") {
			let body: unknown;
			try {
				body = await request.json();
			} catch {
				return reject();
			}
			const rawIds = (body as { m?: unknown })?.m;
			if (!Array.isArray(rawIds) || rawIds.length === 0 || rawIds.length > MAX_FETCH_IDS) {
				return reject();
			}
			const ids: string[] = [];
			for (const raw of rawIds) {
				if (!isValidId(raw)) return reject();
				ids.push(raw);
			}
			const groups = [...groupByShard(ids, (id) => id)];
			const results = await Promise.all(
				groups.map(([shard, group]) => env.MAILBOX.getByName(shard).collect(group, now))
			);
			return Response.json(
				{ items: results.flat() },
				{ headers: { "cache-control": "no-store" } }
			);
		}

		return new Response(null, { status: 404 });
	},
} satisfies ExportedHandler<Env>;
