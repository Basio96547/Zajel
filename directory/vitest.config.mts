import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

/**
 * Runs the suite inside workerd itself, against a real local D1 — not a mock
 * of one. That matters more here than it usually would: half of what this
 * service guarantees is enforced by SQLite (the username primary key is what
 * makes "first claim wins" true, and the nonce primary key is what makes an
 * introduction-fetch proof one-shot), and a hand-written fake of the D1 API
 * would be asserting against my own idea of those constraints rather than
 * against the engine that will actually run them.
 *
 * Bindings come from wrangler.jsonc so the tests exercise the deployed
 * configuration, and schema.sql is applied by the suite itself (see
 * test/helpers.ts) so a schema change that breaks a query fails here rather
 * than in production.
 */
export default defineConfig({
	plugins: [cloudflareTest({ wrangler: { configPath: "./wrangler.jsonc" } })],
});
