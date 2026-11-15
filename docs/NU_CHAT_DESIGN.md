# NU Chat — Architecture & Recovery Design

> Status: **v1 implemented on Android; iOS keeps v2.** Server is the single
> source of truth for platform → node mapping. Live Worker version: see
> `wrangler deploy` output. Canonical server source: `cloudflare/nu-chat-worker/`
> (deployed from the VPS copy at `/root/nu-chat/backend/nu-chat-worker` with
> `npx wrangler deploy -c wrangler.production.jsonc`).

## 1. Two protocols, one data space

Android speaks **v1 (legacy)**, iOS speaks **v2**. Both hit the same Worker at
`https://nu-pe-chat.cc.cd`:

```
Android →  https://nu-pe-chat.cc.cd/Android/chat/…   → v1 handler (legacy.js)
iOS     →  https://nu-pe-chat.cc.cd/iOS/chat/v2/…    → v2 handler (v2.js)
```

The `/Android/` and `/iOS/` prefixes are stripped at the top of the fetch
handler (`index.js`), so both protocols share one router. Data flows into the
same D1 tables keyed by `subject` (derived from the main-station account id),
so **a user registered on Android is reachable from iOS and vice versa** —
neither client needs to implement the other's protocol. This is the
"server-side conversion" model the project was designed around.

### GET-as-POST alias

Networks that RST plain GETs can send `POST /…?__alias=1` instead; the Worker
rewrites it to the real verb before routing. Implemented in `index.js`
(`new Request(url, { method: 'GET', headers })` — cloning via `Request(req)`
throws in Workers, do not "simplify" it back).

## 2. First-launch device registration

Purpose: a fresh install must learn "which chat node do I use" from the server,
and **after reinstalls it must be recognized instantly, with no user action**.

```
device_code = SHA-256("nu-device-v1" | user_id | platform)   // 64 hex
```

- Derived from the main-station account id (`my_user_id`), never stored.
- Same account ⇒ same code ⇒ **reinstall yields the same code**; the server
  sees an existing row and answers immediately.
- Registering is fire-and-forget: on profile refresh (`MainActivity`), a
  background thread calls `ChatDeviceRegistry.ensureRegistered()`:

```
POST /Android/chat/device/register
     { device_code, platform: "android", app_version }
  →  { ok, data: { chat_base, entry_prefix, protocol, … } }
```

- Server stores/refreshes `device_registry(subject, device_code, …)`.
- Response `chat_base` is written back to the `chat_server` pref; all
  subsequent chat traffic targets it.
- Idempotent: a shared-preferences flag prevents repeat calls; logout clears
  the flag (`ChatDeviceRegistry.reset`) so the next account re-registers.
- If registration fails (offline, ECH-less old Android being RST'd), nothing
  breaks — the built-in default base is already correct; it just retries on
  the next profile refresh.

**Trade-off worth knowing:** because the code derives from the account, two
different phones running the same account produce the same code and share one
registry row. Per-physical-device identity would require a locally generated
random code, which contradicts the reinstall-recognition requirement. We chose
reinstall-recognition.

## 3. Private key backup (B1 + B3)

End-to-end encryption is never weakened: **the server only ever holds
ciphertext.**

```
payload = [u32 priv_len][u32 pub_len][PKCS#8 priv][SPKI pub]

KEK     = PBKDF2-HMAC-SHA256(secret, salt, 210000 iterations, 256-bit)
blob    = base64(salt[16] | nonce[12] | AES-256-GCM(KEK, payload))
```

Two blobs are uploaded per user (`PUT /chat/key-backup`):

| blob      | secret                          | purpose                        |
|-----------|---------------------------------|--------------------------------|
| `pwd_blob`  | login password                  | everyday recovery path (B1)    |
| `code_blob` | recovery code (20 chars, Crockford-ish alphabet, no 0/O/1/I/L) | password-loss recovery (B3) |

The server additionally stores `code_hash = SHA-256(normalized code)` so the
"forgot password" flow can locate the row (`POST /chat/key-backup/lookup`)
without ever seeing the code itself.

Recovery (`ChatKeyBackupService`):

- `restoreWithPassword(ctx, password)` — GET backup, decrypt `pwd_blob`,
  install keypair, re-register public key.
- `restoreWithCode(ctx, code)` — normalize input (uppercase, strip
  separators), hash it, lookup, decrypt `code_blob`, install.

Recovery codes are shown **once** at first backup (dialog + auto-copy to
clipboard) and re-backups reuse the existing code so a saved code never
silently expires.

## 4. Dialogs

All user-facing dialogs go through `AppDialogs`
(`MaterialAlertDialogBuilder` + `R.style.DialogAnimation`): `tip`, `confirm`,
`choose`, and `input` (single-line, password-capable). Do **not** instantiate
`androidx.appcompat.app.AlertDialog.Builder` directly — that was the reason
the key-backup dialogs looked off-theme; they were migrated in commit
`8efc967`.

## 5. Deployment

```
# on the VPS (holds the canonical source + wrangler OAuth)
cd /root/nu-chat/backend/nu-chat-worker
# schema changes: add migrations/00NN_*.sql, then
npx wrangler d1 migrations apply nu-chat-db --remote -c wrangler.production.jsonc
npx wrangler deploy -c wrangler.production.jsonc
```

Bindings after deploy: `DB` (D1 nu-chat-db), `BARK_ENABLED`,
`BARK_ENCRYPTION_KEY` (secret), `CURSOR_SECRET` (secret). Secrets survive
deploys; never re-enter them.

The repo's `cloudflare/nu-chat-worker/` mirrors the VPS source. When editing
server code, edit here **or** there and copy back — the VPS copy is what
actually deploys, so after changes on either side, sync both.

## 6. Current limits / known issues

- The Android client on this test phone (SDK 29, no ECH network stack) gets
  RST when connecting directly to `nu-pe-chat.cc.cd`; the app has endpoint rotation + the
  `__alias` escape hatch, so it should still connect, but first-contact
  registration may need a retry.
- Old undecryptable history (sent before key rotation) stays undecryptable by
  design — it renders as the "无法解密" error card with a 核对密钥 action in
  the client.
- The recovery-code sheet in the **iOS** client is rendered by the iOS app's
  own components; nothing in this repo controls its styling.
