import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

/**
 * Runs inside workerd against real Durable Objects with real SQLite storage,
 * because the properties worth testing here live in the storage layer: the
 * per-mailbox cap is a DELETE, the TTL is an alarm, and delivery being
 * destructive is a DELETE that has to happen in the same call as the read. A
 * mock of the DO API would only ever confirm what I already believe those do.
 *
 * Bindings and the SQLite-class migration come from wrangler.jsonc, so the
 * tests run against the deployed configuration rather than a parallel one.
 */
export default defineConfig({
	plugins: [cloudflareTest({ wrangler: { configPath: "./wrangler.jsonc" } })],
});
