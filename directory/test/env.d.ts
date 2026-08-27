import type { Env as WorkerEnv } from "../src/index";

declare global {
	/** Makes `env` in a test the same shape the Worker sees — the bindings themselves come from wrangler.jsonc, not from a second declaration here. */
	namespace Cloudflare {
		interface Env extends WorkerEnv {}
	}
}

export {};
