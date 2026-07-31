# IOC feed — format & trust design (DESIGN ONLY, not implemented)

This documents the **structure** of the signed IOC (indicator-of-compromise) list
that a future local firewall (`VpnService`) would consult to block outbound C2
destinations. **No `VpnService` code and no network code exist yet** — the gate for
building them is real-device test results. This file only reserves the shape.

Design rule (from project guidance): **never trust a bare local list.** An IOC feed
is a remote input; it must be signed and verified before it can influence blocking,
or it becomes an injection vector.

## 1. Feed JSON format

```json
{
  "schema": 1,
  "version": 42,
  "updated_at": "2026-07-04T00:00:00Z",
  "source": "hisn-ioc-feed",
  "iocs": [
    { "type": "domain", "value": "bad-c2.example",  "first_seen": "2025-11-01", "source": "amnesty-2025",  "severity": "high" },
    { "type": "ip",     "value": "203.0.113.7",      "first_seen": "2025-12-10", "source": "citizenlab-2025","severity": "high" }
  ],
  "sig": "ed25519:BASE64_DETACHED_SIGNATURE"
}
```

- `type`: `domain` | `ip` (CIDR later).
- `value`: the indicator itself.
- `first_seen` / `source`: provenance — shown to the user so a block is explainable
  ("blocked bad-c2.example — source: amnesty-2025"), never an opaque verdict.
- `severity`: `high` | `medium` (drives whether we block vs. only warn).
- `version`: monotonic integer, for anti-rollback.

## 2. Signature verification (Ed25519 — placeholder reserved)

1. Serialize the object **without** `sig` in a canonical form (sorted keys, no
   whitespace) → payload bytes.
2. Verify the detached Ed25519 signature over those bytes against a **public key
   pinned in the app** (a compiled constant; not fetched).
3. Reject the feed if **any** of these fail:
   - signature invalid,
   - `schema` unknown,
   - `version` ≤ the currently installed version (anti-rollback),
   - `updated_at` older than the installed feed.
4. Only on success, replace the local set **atomically**.

Reserved interfaces (design — do NOT implement until after the real-device gate):

```
data class Ioc(val type: String, val value: String, val firstSeen: String, val source: String, val severity: String)
data class IocSet(val version: Int, val updatedAt: String, val iocs: List<Ioc>)

interface IocVerifier {            // Ed25519 detached verify against the pinned key
    fun verify(payload: ByteArray, signatureBase64: String): Boolean
}
interface IocStore {               // atomic local persistence of the verified set
    fun current(): IocSet
    fun replaceIfNewer(verified: IocSet): Boolean
}
```

## 3. Update source & schedule

- **URL:** a signed JSON at a pinned default (e.g. a raw file in a dedicated repo),
  overridable only in settings, never silently.
- **Cadence:** a dedicated WorkManager job (separate from the scan job), ~daily,
  with ETag/`If-None-Match` to avoid re-download.
- **Offline-first:** the app ships an **embedded initial list**; network updates only
  replace it *after* verification. No feed ⇒ fall back to the embedded set, never to
  "block nothing silently" without telling the user.

## 4. Consumer (future, not built)

The `VpnService` firewall would, per outbound connection, check the destination
domain/IP against the verified `IocSet` and block+log+explain on a match. That code
is **out of scope until the real-device test passes** and this feed is signed.
