// Vite serves the real schema.sql as a string, so the suite applies the
// deployed schema rather than a copy of it.
//
// Ambient on purpose: a wildcard module declaration is only picked up from a
// file with no top-level import or export, so this cannot live in env.d.ts
// next to the Cloudflare.Env augmentation, which needs one.
declare module "*.sql?raw" {
	const contents: string;
	export default contents;
}
