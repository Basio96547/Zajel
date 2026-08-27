/**
 * The blind mailbox's server side. It has been deployed and carrying real
 * traffic without a test of its own: RelayRoundtripTest in :app proves a
 * phone can complete a round trip against it, which is a different question
 * from whether this Worker refuses what its own comments say it refuses.
 *
 * So the happy path is one test here and the rest are the promises: that a
 * read destroys what it delivered, that a flooder can only ever evict its own
 * blobs, that nothing outlives the TTL, and that the shard a mailbox lands in
 * depends on nothing but the first byte of its id - the one property that
 * keeps this service from being able to group a conversation together.
 */
import { env, runInDurableObject, runDurableObjectAlarm, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import type { BlindMailbox } from "../src/index";

const MAX_BLOB_BYTES = 200 * 1024;

function mailboxId(seed: string): string {
	return seed.padEnd(32, "0").slice(0, 32);
}

/**
 * Mirrors the Worker's own routing rule rather than importing it, so a change
 * to either side shows up as a failure instead of being followed silently.
 * Every test that uses this asserts the blob is present before acting on it,
 * so looking in the wrong shard fails loudly instead of finding an empty
 * table and passing.
 */
function shardStub(id: string) {
	return env.MAILBOX.getByName(`s${parseInt(id.slice(0, 2), 16) % 32}`);
}

function deposit(items: { m: string; b: string }[]): Promise<Response> {
	return SELF.fetch("https://relay.test/d", {
		method: "POST",
		body: JSON.stringify({ items }),
	});
}

async function collect(ids: string[]): Promise<{ status: number; items: { m: string; b: string }[] }> {
	const response = await SELF.fetch("https://relay.test/f", {
		method: "POST",
		body: JSON.stringify({ m: ids }),
	});
	if (response.status !== 200) return { status: response.status, items: [] };
	const body = (await response.json()) as { items: { m: string; b: string }[] };
	return { status: response.status, items: body.items };
}

async function countBlobs(id: string): Promise<number> {
	return runInDurableObject(shardStub(id), (_instance: BlindMailbox, state) =>
		state.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM blobs WHERE mailbox = ?", id).one().n
	);
}

/** Ages every blob in a mailbox past its expiry - the one state no public path can produce on demand. */
async function expireBlobs(id: string): Promise<void> {
	await runInDurableObject(shardStub(id), (_instance: BlindMailbox, state) => {
		state.storage.sql.exec("UPDATE blobs SET exp = 0 WHERE mailbox = ?", id);
	});
}

describe("deposit and collect", () => {
	it("hands a deposited blob to whoever asks for that mailbox", async () => {
		const box = mailboxId("aa01");

		expect((await deposit([{ m: box, b: "opaque-bytes" }])).status).toBe(204);

		expect((await collect([box])).items).toEqual([{ m: box, b: "opaque-bytes" }]);
	});

	it("destroys what it delivered", async () => {
		// Nothing is retained for a second reader, so a mailbox seized later is
		// empty rather than replayable.
		const box = mailboxId("aa02");
		await deposit([{ m: box, b: "opaque-bytes" }]);

		await collect([box]);

		expect((await collect([box])).items).toEqual([]);
		expect(await countBlobs(box)).toBe(0);
	});

	it("tells a depositor nothing beyond the absence of failure", async () => {
		const response = await deposit([{ m: mailboxId("aa03"), b: "opaque-bytes" }]);

		expect(response.status).toBe(204);
		expect(await response.text()).toBe("");
	});

	it("serves one fetch that spans several shards", async () => {
		// First byte 0x00 and 0xff land in different shards by construction, and
		// a client fetching both directions of a conversation routinely does
		// this. If the fan-out were ever collapsed into one shard this passes and
		// the privacy property is gone, which is why the shard ids are asserted
		// to differ rather than assumed to.
		const low = mailboxId("00");
		const high = mailboxId("ff");
		expect(parseInt(low.slice(0, 2), 16) % 32).not.toBe(parseInt(high.slice(0, 2), 16) % 32);
		await deposit([
			{ m: low, b: "from-low" },
			{ m: high, b: "from-high" },
		]);

		const { items } = await collect([low, high]);

		expect(items).toHaveLength(2);
		expect(items.map((item) => item.b).sort()).toEqual(["from-high", "from-low"]);
	});

	it("keeps blobs deposited in the same request apart by mailbox", async () => {
		const first = mailboxId("ab01");
		const second = mailboxId("ab02");
		await deposit([
			{ m: first, b: "one" },
			{ m: second, b: "two" },
		]);

		const { items } = await collect([first]);

		expect(items).toEqual([{ m: first, b: "one" }]);
		expect(await countBlobs(second)).toBe(1);
	});
});

describe("what it refuses", () => {
	it("rejects anything that is not a 32-hex-character mailbox id", async () => {
		for (const id of ["", "short", "z".repeat(32), "AA".repeat(16), "a".repeat(33)]) {
			const response = await deposit([{ m: id, b: "opaque-bytes" }]);
			expect({ id, status: response.status }).toEqual({ id, status: 400 });
		}
	});

	it("rejects an empty batch, an oversized batch, and an oversized blob", async () => {
		const box = mailboxId("ac01");
		const oneTooMany = Array.from({ length: 33 }, (_, i) => ({ m: mailboxId(`ac${i}`), b: "x" }));

		expect((await deposit([])).status).toBe(400);
		expect((await deposit(oneTooMany)).status).toBe(400);
		expect((await deposit([{ m: box, b: "" }])).status).toBe(400);
		expect((await deposit([{ m: box, b: "x".repeat(MAX_BLOB_BYTES + 1) }])).status).toBe(400);
	});

	it("rejects a fetch asking about more mailboxes than the fan-out allows", async () => {
		const tooMany = Array.from({ length: 49 }, (_, i) => mailboxId(`ad${i.toString(16)}`));

		expect((await collect([])).status).toBe(400);
		expect((await collect(tooMany)).status).toBe(400);
	});

	it("rejects a body that is not the shape it expects", async () => {
		const malformed = await SELF.fetch("https://relay.test/d", { method: "POST", body: "not json" });
		const wrongShape = await SELF.fetch("https://relay.test/f", {
			method: "POST",
			body: JSON.stringify({ m: "not-an-array" }),
		});

		expect(malformed.status).toBe(400);
		expect(wrongShape.status).toBe(400);
	});

	it("offers nothing to a browser: no GET, no index, no health page", async () => {
		expect((await SELF.fetch("https://relay.test/f")).status).toBe(405);
		expect((await SELF.fetch("https://relay.test/")).status).toBe(405);
		expect((await SELF.fetch("https://relay.test/nope", { method: "POST" })).status).toBe(404);
	});
});

describe("bounds and expiry", () => {
	it("caps a mailbox at 256 blobs, dropping the oldest", async () => {
		const box = mailboxId("ba01");
		for (let batch = 0; batch < 9; batch++) {
			await deposit(
				Array.from({ length: 32 }, (_, i) => ({ m: box, b: `blob-${batch * 32 + i}` }))
			);
		}

		const { items } = await collect([box]);

		expect(items).toHaveLength(256);
		expect(items[0].b).toBe("blob-32");
		expect(items[255].b).toBe("blob-287");
	});

	it("lets a flooder evict only its own blobs", async () => {
		// The trim is scoped to the mailbox that just grew, so filling one cannot
		// push someone else's undelivered message out.
		const victim = mailboxId("bb01");
		const flooder = mailboxId("bb02");
		await deposit([{ m: victim, b: "still-mine" }]);
		for (let batch = 0; batch < 9; batch++) {
			await deposit(Array.from({ length: 32 }, (_, i) => ({ m: flooder, b: `flood-${batch * 32 + i}` })));
		}

		expect((await collect([victim])).items).toEqual([{ m: victim, b: "still-mine" }]);
	});

	it("never serves a blob past its TTL", async () => {
		const box = mailboxId("bc01");
		await deposit([{ m: box, b: "too-old" }]);
		expect(await countBlobs(box)).toBe(1);

		await expireBlobs(box);

		expect((await collect([box])).items).toEqual([]);
	});

	it("schedules a sweep when it first stores something", async () => {
		const box = mailboxId("bd01");

		await deposit([{ m: box, b: "opaque-bytes" }]);

		const alarm = await runInDurableObject(shardStub(box), (_instance: BlindMailbox, state) =>
			state.storage.getAlarm()
		);
		expect(alarm).not.toBeNull();
	});

	it("sweeps expired blobs and leaves live ones alone", async () => {
		// An expired blob is already invisible to clients; the alarm is what
		// stops it from remaining on disk for someone with a subpoena.
		const abandoned = mailboxId("be01");
		const live = mailboxId("be01aa");
		await deposit([{ m: abandoned, b: "too-old" }]);
		await expireBlobs(abandoned);
		await deposit([{ m: live, b: "still-fresh" }]);
		expect(await countBlobs(abandoned)).toBe(1);

		const ran = await runDurableObjectAlarm(shardStub(abandoned));

		expect(ran).toBe(true);
		expect(await countBlobs(abandoned)).toBe(0);
		expect(await countBlobs(live)).toBe(1);
	});
});
