-- sm-directory schema.
--
-- Two unrelated concerns share this database only because both are small and
-- both are genuinely new persistent state for this system (see src/index.ts's
-- module doc for why that's an explicit, considered choice and not scope
-- creep): a public username claim, and a short-lived queue for introduction
-- requests that a username lookup made possible to address in the first
-- place. Neither table is sharded the way relay/'s blob store is — sharding
-- there exists to stop the relay correlating which mailboxes belong to one
-- conversation, and there is nothing left to hide here: a username claim is
-- meant to be public, and an introduction mailbox id is already computable
-- by anyone who looks the owner up (see core/.../MailboxToken.introMailboxId).

-- No user_id column, deliberately: the directory only ever resolves
-- username -> (identityPublicKey, signingPublicKey). The app-internal random
-- userId each device generates for itself (UserProfile.generateSecureId())
-- travels peer-to-peer inside the sealed intro_request/intro_accept payload,
-- the same way it already travels inside a QR payload today — this service
-- never needs to see or store it.
CREATE TABLE IF NOT EXISTS usernames (
  username             TEXT PRIMARY KEY,   -- ^[a-z0-9_]{3,20}$, enforced in code before every write
  identity_public_key  TEXT NOT NULL UNIQUE,  -- hex, X25519, 32 bytes
  signing_public_key   TEXT NOT NULL,         -- hex, Ed25519, 32 bytes
  claimed_at           INTEGER NOT NULL
);

-- One registered username per identity for v1 — no transfer, no dispute
-- flow. A device that loses its keys loses its username; it claims a new
-- one, same as it would generate a new identity today.
CREATE INDEX IF NOT EXISTS idx_usernames_identity ON usernames(identity_public_key);

CREATE TABLE IF NOT EXISTS introductions (
  rowid    INTEGER PRIMARY KEY AUTOINCREMENT,
  mailbox  TEXT NOT NULL,   -- introMailboxId(recipientIdentityPublicKey) — see src/crypto.ts
  body     TEXT NOT NULL,   -- opaque: a sealed, signed intro_request or intro_accept envelope
  exp      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_introductions_mailbox ON introductions(mailbox);
CREATE INDEX IF NOT EXISTS idx_introductions_exp ON introductions(exp);

-- Spent introduction-fetch proofs.
--
-- `/introductions/fetch` is authorized by a live Ed25519 signature over
-- (nonce, timestamp), and DirectoryProtocol.introFetchSigningPayload's doc
-- in :core promises that a captured proof "can't be replayed to drain a
-- mailbox a second time". A signature alone cannot keep that promise: it is
-- a pure function of bytes, and anyone holding a copy can send them again.
-- Only the server refusing a nonce it has already honoured makes it true, so
-- one is recorded here the moment its proof verifies, and a repeat is
-- refused.
--
-- Rows live exactly as long as the proof they belong to could still pass the
-- handler's timestamp check (exp = timestamp + FETCH_TIMESTAMP_TOLERANCE_MS);
-- past that instant the timestamp check refuses the replay unaided and the
-- row is dead weight, swept by the same cron that sweeps introductions.
CREATE TABLE IF NOT EXISTS intro_fetch_nonces (
  nonce TEXT PRIMARY KEY,   -- hex, exactly as supplied; only ever compared, never decoded
  exp   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_intro_fetch_nonces_exp ON intro_fetch_nonces(exp);
