import type { Env as WorkerEnv } from "../src/index";

declare global {
	/** Bindings come from wrangler.jsonc; this only tells the tests what shape to expect. */
	namespace Cloudflare {
		interface Env extends WorkerEnv {}
	}
}

export {};
