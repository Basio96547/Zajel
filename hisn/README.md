# حصن (Hisn) — Security Hygiene Auditor

A standalone Android app (`com.hisn.app`, its own APK) that surfaces device
security-hygiene indicators a **non-root** app is actually allowed to read.

> **Honest scope.** حصن sells *visibility*, not *protection*. It raises the cost
> for an attacker and shows you what you can't see (risky settings, sideloaded
> apps, accessibility abuse). It **does not** detect or stop nation-state spyware
> (Pegasus-class), which runs above app privilege. This is stated in-app.

## Build & test (one command each)

```bash
# from the repo root (SecureMessenger/)
./gradlew :hisn:assembleDebug        # build the APK
./gradlew :hisn:testDebugUnitTest    # run the pure-logic unit tests (JVM, no device)
```

The detection/scoring/chain logic is pure Kotlin and runs on the JVM — see
`AuditLogicTest`. (The messenger's crypto tests need a device because of libsodium;
حصن's core logic does not.)

## What it checks (8 signals, no root)

| Check | Source | Rule |
|---|---|---|
| Screen lock | `KeyguardManager.isDeviceSecure` | absent → HIGH |
| Security patch age | `Build.VERSION.SECURITY_PATCH` | >5 mo → HIGH, >2 mo → MEDIUM |
| USB debugging | `Settings.Global.ADB_ENABLED` | on → MEDIUM |
| Root indicators | `su` binaries / `test-keys` | present → HIGH |
| Accessibility services | `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` | any 3rd-party → HIGH |
| Device admins | `DevicePolicyManager.activeAdmins` | any 3rd-party → MEDIUM |
| Sideloaded apps | `getInstallSourceInfo` vs known stores | any → MEDIUM |
| Sensitive-permission combos | granted mic/cam/sms/… | 3+ on one app → MEDIUM |

All decisions live in `Rules.kt` (pure) so the app and the tests share one path.

## Hygiene score

`HygieneScore.compute(findings)` → `100 - Σ penalty(severity)`, floored at 0
(CRITICAL 40, HIGH 25, MEDIUM 8, LOW 3). The UI shows the **real** computed
number, never a placeholder. Each scan's score is stored in the audit log.

## Audit log — tamper-evident (the priority feature)

The point isn't a daily score; it's letting a user later demonstrate their device
state *was recorded on a date and hasn't been edited since*.

- **At rest:** `EncryptedSharedPreferences` (AES-256, device-bound master key).
- **Integrity:** every scan is one link in a SHA-256 **hash chain** (`AuditChain`):
  `hash = SHA256(timestamp|score|counts|prevHash)`. Altering, deleting, or
  reordering any past entry breaks every hash after it — proven by
  `AuditLogicTest`.
- **Export:** `AuditLog.export()` emits portable JSON `{entries[], head,
  deviceSeal}` where `deviceSeal = HMAC-SHA256(deviceKey, head)`.
  `verifyExport()` re-checks the chain **and** the seal.

### Import & verify (`VerifyActivity`)
`OpenDocument` (SAF) picks an exported JSON; `AuditLog.verifyImport` recomputes
the chain and re-checks the seal, and the screen shows a two-tier result:
- **Chain integrity** — device-independent (pure SHA-256): proves entries weren't
  altered / deleted / reordered, and points at the first broken index if not.
- **Device seal** — only confirmable on the originating device. On any other
  device the screen honestly says integrity is proven but the seal can't be
  checked here.
Plus a summary (count, date range) and a score-over-time timeline.

### Known limitation (documented, not hidden)
The HMAC seal is **self-verifiable only** (same device). Third-party
non-repudiation (a lawyer's expert verifying without the device) needs asymmetric
signing + a trusted timestamp — a deliberate future step, not shipped.

## Background monitoring & change alerts
`HygieneScanner` is the single scan path shared by the UI and the background:
- **Scheduled:** a WorkManager periodic job (`HygieneScanWorker`, ~24h) scans in
  the background.
- **On change:** `PackageChangeReceiver` runs an immediate scan when an app is
  installed/removed/replaced.
- **Alerts:** `ChangeDetector` compares the new scan to the last snapshot and
  `HisnNotifications` posts **one** notification only when something meaningful
  changed — worsened (red, e.g. "خدمات إمكانية الوصول: من سليم إلى خطر") or
  improved (green) — always with the reason and old→new score. **No alert without
  a clear reason** (verified: identical scans produce nothing).

## Scan vs. fix
حصن currently only **reads and records**. Any remediation (disabling USB
debugging, revoking accessibility) is deferred and must require **explicit user
consent** — never automatic.

## Roadmap (ordered)
1. Validate the score across 5 real devices (rooted + stock) before promoting it.
2. Local firewall (`VpnService`) blocking known C2 destinations.
3. IOC list fetched from a **signed** source (e.g. signed JSON on GitHub),
   signature verified before import — never a bare local list.
4. Asymmetric-signed audit exports for third-party verification.

## Reading list for contributors (#10)
Read **published MVT / Amnesty / Citizen Lab forensic reports**, not just docs, to
understand how Pegasus is actually found (anomalous WebKit processes, missing
`shutdown.log`, etc.). Most of those are iOS-backup, computer-side signals — a
reminder of what an on-device Android app honestly *cannot* see, and why حصن's
scope is what it is.
