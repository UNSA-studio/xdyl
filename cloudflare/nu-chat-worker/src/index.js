var KEEP_FOREVER = 86400000 * 365000;
var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// src/common.js
var AUTH_URL = "https://login.lanternwaves.fun/user/profile";
var DAY = 864e5;
var MAX_BODY = 524288;
var ApiError = class extends Error {
  static {
    __name(this, "ApiError");
  }
  constructor(status, code, message = code, headers = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.headers = headers;
  }
};
var fail = /* @__PURE__ */ __name((status, code) => {
  throw new ApiError(status, code);
}, "fail");
function response(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), { status, headers: {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
    "Access-Control-Allow-Headers": "Authorization,Content-Type,X-Chunk-SHA256",
    ...headers
  } });
}
__name(response, "response");
function object(value, required, optional = []) {
  if (!value || typeof value !== "object" || Array.isArray(value) || required.some((k) => !Object.hasOwn(value, k)) || Object.keys(value).some((k) => !required.includes(k) && !optional.includes(k))) fail(400, "INVALID_REQUEST");
  return value;
}
__name(object, "object");
function text(value, max = 128) {
  if (typeof value !== "string" || !value.length || value.length > max) fail(400, "INVALID_REQUEST");
  return value;
}
__name(text, "text");
function username(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_.-]{1,64}$/.test(value) || value === "." || value === "..") fail(400, "INVALID_REQUEST");
  return value;
}
__name(username, "username");
function uuid(value) {
  if (typeof value !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) fail(400, "INVALID_REQUEST");
  return value.toLowerCase();
}
__name(uuid, "uuid");
function sequence(value) {
  if (typeof value !== "string" || !/^(0|[1-9][0-9]{0,18})$/.test(value) || BigInt(value) > 9223372036854775807n) fail(400, "INVALID_REQUEST");
  return value;
}
__name(sequence, "sequence");
function query(url, allowed) {
  const seen = /* @__PURE__ */ new Set();
  for (const [key2] of url.searchParams) {
    if (!allowed.includes(key2) || seen.has(key2)) fail(400, "INVALID_REQUEST");
    seen.add(key2);
  }
}
__name(query, "query");
async function body(req) {
  if (!/^application\/json(?:\s*;|$)/i.test(req.headers.get("Content-Type") || "")) fail(415, "INVALID_REQUEST");
  const reader = req.body?.getReader();
  if (!reader) fail(400, "INVALID_REQUEST");
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > MAX_BODY) {
        await reader.cancel();
        fail(413, "PAYLOAD_TOO_LARGE");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch {
    fail(400, "INVALID_REQUEST");
  }
}
__name(body, "body");
async function noBody(req) {
  if (req.body) {
    const r = req.body.getReader();
    try {
      const v = await r.read();
      if (!v.done) {
        await r.cancel();
        fail(400, "INVALID_REQUEST");
      }
    } finally {
      r.releaseLock();
    }
  }
}
__name(noBody, "noBody");
function bearer(req) {
  const h = req.headers.get("Authorization") || "";
  if (h.length > 8192 || !/^Bearer [^\s,]+$/i.test(h)) fail(401, "UNAUTHORIZED");
  return h.slice(7);
}
__name(bearer, "bearer");
/* AUTH_CACHE: 同一 token 60 秒内不重复回源主站（每次回源要 200ms~2s，还会因主站抖动返回 503） */
const __authCache = new Map();
async function mainIdentityCached(req, fetcher) {
  const t = bearer(req);
  const now = Date.now();
  const hit = __authCache.get(t);
  if (hit && hit.exp > now) return hit.identity;
  const identity = await mainIdentity(req, fetcher);
  __authCache.set(t, { identity: identity, exp: now + 60000 });
  if (__authCache.size > 200) __authCache.clear();
  return identity;
}
async function mainIdentity(req, fetcher) {
  const token = bearer(req);
  let r, j;
  try {
    r = await fetcher(AUTH_URL, {
      headers: { Authorization: `Bearer ${token}`, "User-Agent": "NU-Chat/3.0" },
      redirect: "manual",
      signal: AbortSignal.timeout(8e3)
    });
    if (r.status === 401 || r.status === 403) fail(401, "UNAUTHORIZED");
    if (!r.ok) fail(503, "TEMPORARILY_UNAVAILABLE");
    j = await r.json();
  } catch (e) {
    if (e instanceof ApiError) throw e;
    fail(503, "TEMPORARILY_UNAVAILABLE");
  }
  if (j?.code !== void 0) {
    const code = Number(j.code);
    if (code === 401 || code === 403) fail(401, "UNAUTHORIZED");
    if (!Number.isInteger(code) || code < 200 || code >= 300) fail(503, "TEMPORARILY_UNAVAILABLE");
  }
  const d = j?.data ?? j;
  if (!d || typeof d.username !== "string") fail(409, "IDENTITY_UNSUPPORTED");
  if (d.banned === true || d.banned === 1 || d.banned === "1" || d.disabled === true) fail(403, "FORBIDDEN");
  return { username: username(d.username), id: d.id ?? d.user_id };
}
__name(mainIdentity, "mainIdentity");
function subject(identity) {
  const id = identity.id;
  if (typeof id === "number" && Number.isSafeInteger(id) && id > 0 || typeof id === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(id) && id !== "0") return `account:${id}`;
  fail(409, "IDENTITY_UNSUPPORTED");
}
__name(subject, "subject");
function base64(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes)));
}
__name(base64, "base64");
function unbase64(value, max = 32768) {
  if (typeof value !== "string" || !value.length || value.length > max * 1.4 + 4 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) fail(400, "INVALID_REQUEST");
  let bytes;
  try {
    bytes = Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
  } catch {
    fail(400, "INVALID_REQUEST");
  }
  if (bytes.length > max || base64(bytes) !== value) fail(400, "INVALID_REQUEST");
  return bytes;
}
__name(unbase64, "unbase64");
async function publicKey(algorithm, value) {
  const bytes = unbase64(value, 1024);
  const spec = algorithm === "X25519" ? { name: "X25519" } : algorithm === "P256" ? { name: "ECDH", namedCurve: "P-256" } : null;
  if (!spec) fail(400, "UNSUPPORTED_ALGORITHM");
  const prefix = algorithm === "X25519" ? [48, 42, 48, 5, 6, 3, 43, 101, 110, 3, 33, 0] : [48, 89, 48, 19, 6, 7, 42, 134, 72, 206, 61, 2, 1, 6, 8, 42, 134, 72, 206, 61, 3, 1, 7, 3, 66, 0, 4];
  if (bytes.length !== (algorithm === "X25519" ? 44 : 91) || prefix.some((b, i) => bytes[i] !== b)) fail(400, "INVALID_REQUEST");
  try {
    await crypto.subtle.importKey("spki", bytes, spec, false, []);
  } catch {
    fail(400, "INVALID_REQUEST");
  }
}
__name(publicKey, "publicKey");
var canonical = /* @__PURE__ */ __name((value) => Array.isArray(value) ? `[${value.map(canonical).join(",")}]` : value !== null && typeof value === "object" ? `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`).join(",")}}` : JSON.stringify(value), "canonical");
async function hash(value) {
  return base64(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
}
__name(hash, "hash");
function secret(env, name) {
  const raw = env[name];
  if (typeof raw !== "string" || !/^[A-Za-z0-9+/]{43}=$/.test(raw)) fail(503, "NOT_CONFIGURED");
  const key2 = unbase64(raw, 32);
  if (key2.length !== 32) fail(503, "NOT_CONFIGURED");
  return key2;
}
__name(secret, "secret");
var statement = /* @__PURE__ */ __name((db, sql, ...args) => db.prepare(sql).bind(...args), "statement");
var rows = /* @__PURE__ */ __name(async (stmt) => (await stmt.all()).results, "rows");
function guard(db, sql, ...args) {
  return statement(db, `UPDATE v2_guard SET valid=CASE WHEN (${sql}) THEN 1 ELSE 0 END WHERE id=1`, ...args);
}
__name(guard, "guard");
async function atomic(db, statements, code = "REVISION_CONFLICT") {
  try {
    return await db.batch(statements);
  } catch (e) {
    if (/CHECK constraint failed|UNIQUE constraint failed|FOREIGN KEY constraint failed/i.test(String(e))) fail(409, code);
    throw e;
  }
}
__name(atomic, "atomic");
async function rate(db, scope, max, now) {
  const window = Math.floor(now / 6e4);
  const r = await statement(db, `INSERT INTO v2_rate_limits(scope,window,count) VALUES(?,?,1)
    ON CONFLICT(scope) DO UPDATE SET count=CASE WHEN window=excluded.window THEN count+1 ELSE 1 END,
    window=excluded.window RETURNING count`, scope, window).first();
  if (r.count > max) throw new ApiError(429, "RATE_LIMITED", "RATE_LIMITED", { "Retry-After": String(60 - Math.floor(now / 1e3) % 60) });
}
__name(rate, "rate");

// src/store.js
var messagePayload = /* @__PURE__ */ __name((e = "e") => `json_set(${e}.payload,
  '$.sender_username',(SELECT username FROM v2_accounts WHERE subject=json_extract(${e}.payload,'$.sender_subject')),
  '$.recipient_username',(SELECT username FROM v2_accounts WHERE subject=json_extract(${e}.payload,'$.recipient_subject')),
  '$.envelope.sender_public_key',COALESCE(json_extract(${e}.payload,'$.envelope.sender_public_key'),
    (SELECT public_key FROM v2_keys WHERE key_id=json_extract(${e}.payload,'$.envelope.sender_key_id'))))`, "messagePayload");
async function authenticate(req, db, now) {
  const tokenHash = await hash(bearer(req));
  const auth = await statement(db, `SELECT d.*,a.username,CAST(d.key_revision AS TEXT) AS revision,
    CAST(d.ack_seq AS TEXT) AS ack,CAST(d.floor_seq AS TEXT) AS floor,
    CAST(d.max_issued_seq AS TEXT) AS issued,s.expires_at FROM v2_sessions s
    JOIN v2_devices d ON d.device_id=s.device_id JOIN v2_accounts a ON a.subject=d.subject
    WHERE s.token_hash=?`, tokenHash).first();
  if (!auth || auth.expires_at <= now) fail(401, "SESSION_EXPIRED");
  if (auth.revoked) fail(403, "DEVICE_REVOKED");
  return { ...auth, tokenHash };
}
__name(authenticate, "authenticate");
function sessionGuard(db, auth, now) {
  return guard(db, `EXISTS(SELECT 1 FROM v2_sessions s JOIN v2_devices d ON d.device_id=s.device_id
    WHERE s.token_hash=? AND s.expires_at>? AND d.revoked=0)`, auth.tokenHash, now);
}
__name(sessionGuard, "sessionGuard");
function active(auth) {
  if (!auth.active) fail(403, "DEVICE_NOT_ACTIVE");
}
__name(active, "active");
async function bundle(db, owner) {
  const a = await statement(db, "SELECT subject,username,CAST(keyset_revision AS TEXT) AS keyset_revision FROM v2_accounts WHERE subject=?", owner).first();
  if (!a) fail(404, "USER_NOT_FOUND");
  const devices = await rows(statement(db, `SELECT device_id,CAST(key_revision AS TEXT) AS key_revision FROM v2_devices
    WHERE subject=? AND active=1 AND revoked=0 ORDER BY device_id`, owner));
  const keys = await rows(statement(db, `SELECT k.device_id,k.key_id,k.algorithm,k.public_key FROM v2_keys k
    JOIN v2_devices d ON d.device_id=k.device_id AND d.key_revision=k.revision
    WHERE d.subject=? AND d.active=1 AND d.revoked=0 ORDER BY k.algorithm`, owner));
  for (const d of devices) d.keys = keys.filter((k) => k.device_id === d.device_id).map(({ device_id, ...k }) => k);
  return { ...a, devices };
}
__name(bundle, "bundle");
function keysetEvents(db, owner, now) {
  return statement(db, `INSERT INTO v2_events(device_id,dedup,type,payload,expires_at)
    SELECT d.device_id,'key:'||a.subject||':'||a.keyset_revision,'keyset_changed',
    json_object('type','keyset_changed','subject',a.subject,'keyset_revision',CAST(a.keyset_revision AS TEXT),'changed_at_ms',?),?
    FROM v2_devices d JOIN v2_accounts a ON a.subject=?
    WHERE d.active=1 AND d.revoked=0 AND (d.subject=a.subject OR EXISTS(
      SELECT 1 FROM v2_messages m WHERE m.expires_at>? AND
      ((m.sender=a.subject AND m.recipient=d.subject) OR (m.recipient=a.subject AND m.sender=d.subject))))
    ON CONFLICT(device_id,dedup) DO NOTHING`, now, now + KEEP_FOREVER, owner, now);
}
__name(keysetEvents, "keysetEvents");
async function currentFloor(db, device, now) {
  const r = await statement(db, `SELECT CAST(MAX(floor_seq,COALESCE((SELECT MAX(seq) FROM v2_events
    WHERE device_id=? AND expires_at<=?),0)) AS TEXT) AS floor FROM v2_devices WHERE device_id=?`, device, now, device).first();
  return r.floor;
}
__name(currentFloor, "currentFloor");
async function conversation(db, id, owner) {
  const c = await statement(db, "SELECT * FROM v2_conversations WHERE conversation_id=?", id).first();
  if (!c) fail(404, "MESSAGE_NOT_FOUND");
  if (c.a !== owner && c.b !== owner) fail(403, "FORBIDDEN");
  return c;
}
__name(conversation, "conversation");
async function accepted(db, auth, id) {
  return statement(
    db,
    `SELECT message_id,client_msg_id,conversation_id,CAST(seq AS TEXT) AS conversation_seq,
    accepted_at AS accepted_at_ms,request_hash FROM v2_messages WHERE sender=? AND sender_device=? AND client_msg_id=?`,
    auth.subject,
    auth.device_id,
    id
  ).first();
}
__name(accepted, "accepted");
function acceptance(row, duplicate) {
  const { request_hash: ignored, ...result } = row;
  return { ...result, duplicate };
}
__name(acceptance, "acceptance");

// src/cursor.js
var encode = /* @__PURE__ */ __name((bytes) => base64(bytes).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, ""), "encode");
function decode(s) {
  if (!/^[A-Za-z0-9_-]+$/.test(s)) fail(400, "INVALID_CURSOR");
  try {
    return Uint8Array.from(atob(s.replaceAll("-", "+").replaceAll("_", "/") + "=".repeat((4 - s.length % 4) % 4)), (c) => c.charCodeAt(0));
  } catch {
    fail(400, "INVALID_CURSOR");
  }
}
__name(decode, "decode");
async function key(env) {
  return crypto.subtle.importKey("raw", secret(env, "CURSOR_SECRET"), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}
__name(key, "key");
async function signCursor(env, auth, position, floor) {
  return signOpaque(env, { v: 2, s: auth.subject, d: auth.device_id, p: position, f: floor });
}
__name(signCursor, "signCursor");
async function signOpaque(env, value) {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  return `${encode(bytes)}.${encode(await crypto.subtle.sign("HMAC", await key(env), bytes))}`;
}
__name(signOpaque, "signOpaque");
async function readOpaque(env, raw) {
  if (!raw || raw.length > 2048) fail(400, "INVALID_CURSOR");
  const parts = raw.split(".");
  if (parts.length !== 2) fail(400, "INVALID_CURSOR");
  const bytes = decode(parts[0]);
  if (!await crypto.subtle.verify("HMAC", await key(env), decode(parts[1]), bytes)) fail(400, "INVALID_CURSOR");
  let p;
  try {
    p = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    fail(400, "INVALID_CURSOR");
  }
  return p;
}
__name(readOpaque, "readOpaque");
async function parseCursor(env, auth, raw) {
  const p = await readOpaque(env, raw);
  if (p.v !== 2 || p.s !== auth.subject || p.d !== auth.device_id) fail(403, "FORBIDDEN");
  sequence(p.p);
  sequence(p.f);
  return p;
}
__name(parseCursor, "parseCursor");

// src/social.js
var visible = /* @__PURE__ */ __name((m = "m", viewer = "v") => `${m}.recalled_at IS NULL
  AND EXISTS(SELECT 1 FROM v2_copies cp WHERE cp.owner=${viewer}.owner AND cp.message_id=${m}.message_id AND cp.expires_at>${viewer}.now)
  AND NOT EXISTS(SELECT 1 FROM v2_hidden h WHERE h.owner=${viewer}.owner AND h.message_id=${m}.message_id)
  AND ${m}.seq>COALESCE((SELECT cs.clear_seq FROM v2_conversation_state cs WHERE cs.owner=${viewer}.owner AND cs.conversation_id=${m}.conversation_id),0)`, "visible");
function notifyAccount(db, owner, type, payload, now, id = crypto.randomUUID()) {
  return statement(db, `INSERT INTO v2_events(device_id,dedup,type,payload,expires_at)
    SELECT device_id,?,?,?,? FROM v2_devices WHERE subject=? AND active=1 AND revoked=0
    ON CONFLICT(device_id,dedup) DO NOTHING`, `${type}:${id}`, type, JSON.stringify({ type, ...payload }), now + KEEP_FOREVER, owner);
}
__name(notifyAccount, "notifyAccount");
var filterTick = /* @__PURE__ */ __name((db, owner) => statement(db, `INSERT INTO v2_filter_clock(owner,revision) VALUES(?,1)
  ON CONFLICT(owner) DO UPDATE SET revision=revision+1`, owner), "filterTick");
function deliveryGuard(db, sender, recipient) {
  return guard(db, `NOT EXISTS(SELECT 1 FROM v2_blocks WHERE owner=? AND blocked=?)
    AND (COALESCE((SELECT receive_policy FROM v2_preferences WHERE subject=?),'everyone')='everyone'
    OR (COALESCE((SELECT receive_policy FROM v2_preferences WHERE subject=?),'everyone')='existing'
      AND EXISTS(SELECT 1 FROM v2_messages WHERE sender=? AND recipient=?)))`, recipient, sender, recipient, recipient, recipient, sender);
}
__name(deliveryGuard, "deliveryGuard");
async function preferences(db, owner) {
  const p = await statement(db, `SELECT CAST(revision AS TEXT) AS revision,receive_policy,read_receipts,retention_days FROM v2_preferences WHERE subject=?`, owner).first();
  return p ? { ...p, read_receipts: !!p.read_receipts } : { revision: "0", receive_policy: "everyone", read_receipts: true, retention_days: 365000 };
}
__name(preferences, "preferences");
async function state(db, owner, id) {
  const s = await statement(db, `SELECT CAST(revision AS TEXT) AS revision,pinned,
    CASE WHEN archived_seq>=COALESCE((SELECT MAX(seq) FROM v2_messages WHERE conversation_id=v2_conversation_state.conversation_id),0) THEN archived ELSE 0 END AS archived,
    CAST(archived_seq AS TEXT) AS archived_seq,
    CAST(clear_seq AS TEXT) AS clear_seq FROM v2_conversation_state WHERE owner=? AND conversation_id=?`, owner, id).first();
  return s ? { ...s, pinned: !!s.pinned, archived: !!s.archived } : { revision: "0", pinned: false, archived: false, archived_seq: "0", clear_seq: "0" };
}
__name(state, "state");
async function socialRoute(req, env, db, url, auth, deps) {
  const path = url.pathname;
  const method = req.method;
  const now = deps.now();
  const owner = auth.subject;
  const sg = /* @__PURE__ */ __name(() => sessionGuard(db, auth, now), "sg");
  const ok = /* @__PURE__ */ __name((data) => data === void 0 ? { ok: true } : { ok: true, data }, "ok");
  if (path === "/chat/v2/me/preferences") {
    query(url, []);
    if (method === "GET") {
      await noBody(req);
      return ok(await preferences(db, owner));
    }
    const b = object(await body(req), ["expected_revision"], ["receive_policy", "read_receipts", "retention_days"]);
    sequence(b.expected_revision);
    if (b.receive_policy !== void 0 && !["everyone", "existing", "none"].includes(b.receive_policy)) fail(400, "INVALID_REQUEST");
    if (b.read_receipts !== void 0 && typeof b.read_receipts !== "boolean") fail(400, "INVALID_REQUEST");
    if (b.retention_days !== void 0 && ![1, 7, 30].includes(b.retention_days)) fail(400, "INVALID_REQUEST");
    const old = await preferences(db, owner);
    const next = { ...old, ...b };
    await atomic(db, [
      sg(),
      guard(db, `COALESCE((SELECT revision FROM v2_preferences WHERE subject=?),0)=CAST(? AS INTEGER)`, owner, b.expected_revision),
      statement(
        db,
        `INSERT INTO v2_preferences(subject,revision,receive_policy,read_receipts,retention_days) VALUES(?,1,?,?,?)
        ON CONFLICT(subject) DO UPDATE SET revision=revision+1,receive_policy=excluded.receive_policy,read_receipts=excluded.read_receipts,retention_days=excluded.retention_days`,
        owner,
        next.receive_policy,
        next.read_receipts ? 1 : 0,
        next.retention_days
      ),
      statement(db, `UPDATE v2_copies SET expires_at=MIN(expires_at,(SELECT accepted_at FROM v2_messages m WHERE m.message_id=v2_copies.message_id)+?) WHERE owner=?`, next.retention_days * DAY, owner),
      statement(db, `UPDATE v2_events SET expires_at=MIN(expires_at,(SELECT cp.expires_at FROM v2_copies cp WHERE cp.owner=? AND cp.message_id=v2_events.message_id))
        WHERE type='message' AND device_id IN(SELECT device_id FROM v2_devices WHERE subject=?)`, owner, owner),
      notifyAccount(db, owner, "preferences_changed", {}, now)
    ]);
    return ok(await preferences(db, owner));
  }
  if (path === "/chat/v2/blocks" && method === "GET") {
    query(url, []);
    await noBody(req);
    return ok({ blocked: await rows(statement(db, "SELECT blocked AS subject,created_at AS blocked_at_ms FROM v2_blocks WHERE owner=? ORDER BY created_at,blocked", owner)) });
  }
  let match = path.match(/^\/chat\/v2\/blocks\/([^/]+)$/);
  if (match) {
    query(url, []);
    let peer;
    try {
      peer = decodeURIComponent(match[1]);
    } catch {
      fail(400, "INVALID_REQUEST");
    }
    text(peer);
    if (peer === owner) fail(400, "INVALID_REQUEST");
    if (!await statement(db, "SELECT 1 FROM v2_accounts WHERE subject=?", peer).first()) fail(404, "USER_NOT_FOUND");
    if (method === "PUT") {
      object(await body(req), []);
      await atomic(db, [
        sg(),
        statement(db, "INSERT INTO v2_blocks(owner,blocked,created_at) VALUES(?,?,?) ON CONFLICT(owner,blocked) DO NOTHING", owner, peer, now),
        statement(db, `UPDATE v2_outbox SET state='cancelled',lease_token=NULL,lease_until=0 WHERE state IN('queued','leased') AND device_id IN
          (SELECT device_id FROM v2_devices WHERE subject=?) AND message_id IN(SELECT message_id FROM v2_messages WHERE sender=? AND recipient=?)`, owner, peer, owner),
        notifyAccount(db, owner, "blocks_changed", {}, now)
      ]);
    } else {
      await noBody(req);
      await atomic(db, [sg(), statement(db, "DELETE FROM v2_blocks WHERE owner=? AND blocked=?", owner, peer), notifyAccount(db, owner, "blocks_changed", {}, now)]);
    }
    return ok();
  }
  if (path === "/chat/v2/conversations" && method === "GET") {
    query(url, ["cursor", "limit", "archived"]);
    await noBody(req);
    const limit = Number(url.searchParams.get("limit") ?? "50");
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) fail(400, "INVALID_REQUEST");
    const archived = url.searchParams.get("archived") ?? "false";
    if (!["true", "false", "all"].includes(archived)) fail(400, "INVALID_REQUEST");
    let cursor = { pin: 2, last: "9223372036854775807", id: "" };
    if (url.searchParams.has("cursor")) {
      cursor = await readOpaque(env, url.searchParams.get("cursor"));
      if (cursor.scope !== `conversations:${archived}` || cursor.owner !== owner || cursor.device !== auth.device_id) fail(403, "FORBIDDEN");
      sequence(cursor.last);
      if (![0, 1, 2].includes(cursor.pin) || typeof cursor.id !== "string") fail(400, "INVALID_CURSOR");
    }
    const result = await rows(statement(
      db,
      `WITH v AS(SELECT ? AS owner,? AS device,? AS now), items AS(
      SELECT c.conversation_id,CASE WHEN c.a=v.owner THEN c.b ELSE c.a END AS peer_subject,
      (SELECT username FROM v2_accounts WHERE subject=CASE WHEN c.a=v.owner THEN c.b ELSE c.a END) AS peer_username,
      COALESCE(cs.pinned,0) AS pinned,CASE WHEN cs.archived=1 AND cs.archived_seq>=COALESCE((SELECT MAX(seq) FROM v2_messages WHERE conversation_id=c.conversation_id),0) THEN 1 ELSE 0 END AS archived,
      CAST(COALESCE((SELECT MAX(seq) FROM v2_messages WHERE conversation_id=c.conversation_id),0) AS TEXT) AS last_seq,
      (SELECT message_id FROM v2_messages m WHERE m.conversation_id=c.conversation_id AND ${visible()} ORDER BY m.seq DESC LIMIT 1) AS last_visible_message_id,
      (SELECT COUNT(*) FROM v2_messages m WHERE m.conversation_id=c.conversation_id AND m.recipient=v.owner AND ${visible()}
        AND m.seq>COALESCE((SELECT upto_seq FROM v2_reads WHERE conversation_id=c.conversation_id AND reader=v.owner),0)) AS unread_total,
      (SELECT COUNT(*) FROM v2_messages m WHERE m.conversation_id=c.conversation_id AND m.recipient=v.owner AND ${visible()}
        AND m.seq>COALESCE((SELECT upto_seq FROM v2_reads WHERE conversation_id=c.conversation_id AND reader=v.owner),0)
        AND EXISTS(SELECT 1 FROM v2_events e WHERE e.device_id=v.device AND e.message_id=m.message_id AND e.type='message' AND e.expires_at>v.now)) AS unread_available,
      EXISTS(SELECT 1 FROM v2_messages m WHERE m.conversation_id=c.conversation_id AND ${visible()}
        AND NOT EXISTS(SELECT 1 FROM v2_events e WHERE e.device_id=v.device AND e.message_id=m.message_id AND e.type='message' AND e.expires_at>v.now)) AS history_gap,
      CAST(COALESCE(cs.revision,0) AS TEXT) AS revision
      FROM v2_conversations c CROSS JOIN v LEFT JOIN v2_conversation_state cs ON cs.owner=v.owner AND cs.conversation_id=c.conversation_id WHERE c.a=v.owner OR c.b=v.owner)
      SELECT * FROM items WHERE (?='all' OR archived=?) AND (pinned<? OR (pinned=? AND (CAST(last_seq AS INTEGER)<? OR (CAST(last_seq AS INTEGER)=? AND conversation_id>?))))
      ORDER BY pinned DESC,CAST(last_seq AS INTEGER) DESC,conversation_id LIMIT ?`,
      owner,
      auth.device_id,
      now,
      archived,
      archived === "true" ? 1 : 0,
      cursor.pin,
      cursor.pin,
      cursor.last,
      cursor.last,
      cursor.id,
      limit + 1
    ));
    const page = result.slice(0, limit);
    const last = page.at(-1);
    const summary = await statement(db, `WITH v AS(SELECT ? AS owner,? AS now) SELECT COUNT(*) AS unread_total FROM v2_messages m CROSS JOIN v
      WHERE m.recipient=v.owner AND ${visible()} AND m.seq>COALESCE((SELECT upto_seq FROM v2_reads r WHERE r.conversation_id=m.conversation_id AND r.reader=v.owner),0)`, owner, now).first();
    return ok({
      conversations: page.map((r) => ({ ...r, pinned: !!r.pinned, archived: !!r.archived, history_gap: !!r.history_gap })),
      unread_total: summary.unread_total,
      has_more: result.length > limit,
      next_cursor: last ? await signOpaque(env, { scope: `conversations:${archived}`, owner, device: auth.device_id, pin: last.pinned, last: last.last_seq, id: last.conversation_id }) : null
    });
  }
  match = path.match(/^\/chat\/v2\/conversations\/([^/]+)\/(state|clear|messages)$/);
  if (match) {
    const id = uuid(match[1]);
    await conversation(db, id, owner);
    const action = match[2];
    if (action === "messages") {
      query(url, ["before", "limit"]);
      await noBody(req);
      const before = sequence(url.searchParams.get("before") ?? "9223372036854775807");
      const limit = Number(url.searchParams.get("limit") ?? "100");
      if (!Number.isInteger(limit) || limit < 1 || limit > 200) fail(400, "INVALID_REQUEST");
      const list = await rows(statement(db, `WITH v AS(SELECT ? AS owner,? AS now) SELECT CAST(m.seq AS TEXT) AS seq,${messagePayload()} AS payload FROM v2_messages m CROSS JOIN v
        JOIN v2_events e ON e.message_id=m.message_id AND e.type='message' AND e.device_id=? WHERE m.conversation_id=? AND m.seq<? AND ${visible()} AND e.expires_at>v.now
        ORDER BY m.seq DESC LIMIT ?`, owner, now, auth.device_id, id, before, limit + 1));
      const page = list.slice(0, limit);
      return ok({
        messages: page.map((r) => JSON.parse(r.payload)),
        has_more: list.length > limit,
        next_before: page.at(-1)?.seq ?? null,
        history_scope: "current_device_envelopes"
      });
    }
    query(url, []);
    const current = await state(db, owner, id);
    if (method === "GET") {
      await noBody(req);
      return ok(current);
    }
    const b = object(await body(req), ["expected_revision"], action === "clear" ? ["through_seq"] : ["pinned", "archived"]);
    sequence(b.expected_revision);
    const next = { ...current };
    if (action === "state") for (const key2 of ["pinned", "archived"]) {
      if (b[key2] !== void 0 && typeof b[key2] !== "boolean") fail(400, "INVALID_REQUEST");
      if (b[key2] !== void 0) next[key2] = b[key2];
    }
    const latest = await statement(db, "SELECT CAST(COALESCE(MAX(seq),0) AS TEXT) AS seq FROM v2_messages WHERE conversation_id=?", id).first();
    if (action === "clear") {
      sequence(b.through_seq);
      if (BigInt(b.through_seq) > BigInt(latest.seq)) fail(400, "INVALID_REQUEST");
      next.clear_seq = String(BigInt(b.through_seq) > BigInt(current.clear_seq) ? b.through_seq : current.clear_seq);
    }
    next.archived_seq = next.archived ? latest.seq : "0";
    await atomic(db, [
      sg(),
      guard(db, "COALESCE((SELECT revision FROM v2_conversation_state WHERE owner=? AND conversation_id=?),0)=CAST(? AS INTEGER)", owner, id, b.expected_revision),
      statement(
        db,
        `INSERT INTO v2_conversation_state(owner,conversation_id,revision,pinned,archived,archived_seq,clear_seq) VALUES(?,?,1,?,?,?,?)
        ON CONFLICT(owner,conversation_id) DO UPDATE SET revision=revision+1,pinned=excluded.pinned,archived=excluded.archived,archived_seq=excluded.archived_seq,clear_seq=MAX(v2_conversation_state.clear_seq,excluded.clear_seq)`,
        owner,
        id,
        next.pinned ? 1 : 0,
        next.archived ? 1 : 0,
        next.archived_seq,
        next.clear_seq
      ),
      ...action === "clear" ? [filterTick(db, owner)] : [],
      notifyAccount(db, owner, action === "clear" ? "history_cleared" : "conversation_state_changed", { conversation_id: id, through_seq: next.clear_seq }, now)
    ]);
    return ok(await state(db, owner, id));
  }
  match = path.match(/^\/chat\/v2\/messages\/([^/]+)(\/recall)?$/);
  if (match) {
    query(url, []);
    const id = uuid(match[1]);
    const m = await statement(db, "SELECT * FROM v2_messages WHERE message_id=?", id).first();
    if (!m) fail(404, "MESSAGE_NOT_FOUND");
    if (m.sender !== owner && m.recipient !== owner) fail(403, "FORBIDDEN");
    if (!match[2]) {
      await noBody(req);
      await atomic(db, [
        sg(),
        statement(db, "INSERT INTO v2_hidden(owner,message_id,hidden_at) VALUES(?,?,?) ON CONFLICT(owner,message_id) DO NOTHING", owner, id, now),
        filterTick(db, owner),
        notifyAccount(db, owner, "message_hidden", { message_id: id }, now)
      ]);
      return ok();
    }
    object(await body(req), []);
    if (m.sender !== owner) fail(403, "FORBIDDEN");
    if (m.recalled_at) return ok({ recalled_at_ms: m.recalled_at });
    if (now - m.accepted_at > 6e5) fail(409, "RECALL_WINDOW_EXPIRED");
    await atomic(db, [
      sg(),
      guard(db, "EXISTS(SELECT 1 FROM v2_messages WHERE message_id=? AND accepted_at>=?)", id, now - 6e5),
      statement(db, "UPDATE v2_messages SET recalled_at=COALESCE(recalled_at,?) WHERE message_id=?", now, id),
      statement(db, `UPDATE v2_events SET payload=json_set(json_remove(payload,'$.envelope'),'$.unavailable','recalled') WHERE message_id=? AND type='message'`, id),
      statement(db, "UPDATE v2_outbox SET state='cancelled',lease_token=NULL,lease_until=0 WHERE message_id=? AND state IN('queued','leased')", id),
      filterTick(db, m.sender),
      filterTick(db, m.recipient),
      notifyAccount(db, m.sender, "message_recalled", { message_id: id }, now, id),
      notifyAccount(db, m.recipient, "message_recalled", { message_id: id }, now, id)
    ]);
    return ok({ recalled_at_ms: now });
  }
  return null;
}
__name(socialRoute, "socialRoute");

// src/notifications.js
var dayNames = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
function validateNotificationConfig(config) {
  object(config, [], ["enabled", "muted", "mute_until_ms", "quiet_hours"]);
  for (const k of ["enabled", "muted"]) if (config[k] !== void 0 && typeof config[k] !== "boolean") fail(400, "INVALID_REQUEST");
  if (config.mute_until_ms !== void 0 && (!Number.isSafeInteger(config.mute_until_ms) || config.mute_until_ms < 0)) fail(400, "INVALID_REQUEST");
  if (config.quiet_hours !== void 0 && config.quiet_hours !== null) {
    const h = object(config.quiet_hours, ["timezone", "start", "end", "weekdays"]);
    if (typeof h.timezone !== "string" || h.timezone.length > 64) fail(400, "INVALID_REQUEST");
    try {
      new Intl.DateTimeFormat("en", { timeZone: h.timezone }).format();
    } catch {
      fail(400, "INVALID_TIMEZONE");
    }
    for (const k of ["start", "end"]) if (typeof h[k] !== "string" || !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(h[k])) fail(400, "INVALID_REQUEST");
    const minutes = /* @__PURE__ */ __name((s) => Number(s.slice(0, 2)) * 60 + Number(s.slice(3)), "minutes");
    const duration = (minutes(h.end) - minutes(h.start) + 1440) % 1440;
    if (!duration || duration > 1380 || !Array.isArray(h.weekdays) || !h.weekdays.length || h.weekdays.length > 7 || new Set(h.weekdays).size !== h.weekdays.length || h.weekdays.some((d) => !Number.isInteger(d) || d < 1 || d > 7)) fail(400, "INVALID_REQUEST");
  }
  return config;
}
__name(validateNotificationConfig, "validateNotificationConfig");
function quietUntil(hours, now) {
  if (!hours) return 0;
  const fmt = new Intl.DateTimeFormat("en-US", { timeZone: hours.timezone, weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  const minutes = /* @__PURE__ */ __name((s) => Number(s.slice(0, 2)) * 60 + Number(s.slice(3)), "minutes");
  const start = minutes(hours.start), end = minutes(hours.end);
  const inside = /* @__PURE__ */ __name((time) => {
    const p = Object.fromEntries(fmt.formatToParts(time).map((x) => [x.type, x.value]));
    let day = dayNames.indexOf(p.weekday) || 7;
    const minute = Number(p.hour) * 60 + Number(p.minute);
    if (start < end) return hours.weekdays.includes(day) && minute >= start && minute < end;
    if (minute < end) day = day === 1 ? 7 : day - 1;
    return hours.weekdays.includes(day) && (minute >= start || minute < end);
  }, "inside");
  if (!inside(now)) return 0;
  let high = now + 9e5;
  while (inside(high) && high < now + 3 * 864e5) high += 9e5;
  if (inside(high)) fail(400, "INVALID_QUIET_HOURS");
  let low = high - 9e5;
  while (high - low > 1e3) {
    const mid = Math.floor((low + high) / 2);
    if (inside(mid)) low = mid;
    else high = mid;
  }
  return Math.floor(high / 6e4) * 6e4;
}
__name(quietUntil, "quietUntil");
async function notificationConfig(db, owner, device, conversationId = "") {
  const records = await rows(statement(db, `SELECT scope,owner,conversation_id,config FROM v2_notification_preferences
    WHERE (scope='account' AND owner=?) OR (scope='device' AND owner=?)`, owner, device));
  const merged = { enabled: true, muted: false, mute_until_ms: 0, quiet_hours: null };
  const levels = [["account", owner, ""], ["device", device, ""], ...conversationId ? [["account", owner, conversationId], ["device", device, conversationId]] : []];
  for (const [scope, id, conv] of levels) {
    const r = records.find((r2) => r2.scope === scope && r2.owner === id && r2.conversation_id === conv);
    if (r) Object.assign(merged, JSON.parse(r.config));
  }
  return merged;
}
__name(notificationConfig, "notificationConfig");
async function notificationEligibility(db, job, now) {
  if (!job.message_id) return { allow: true, quietUntil: 0 };
  const row = await statement(db, `WITH v AS(SELECT d.subject AS owner,? AS now FROM v2_devices d WHERE d.device_id=?)
    SELECT m.conversation_id,v.owner FROM v2_messages m CROSS JOIN v WHERE m.message_id=? AND m.recipient=v.owner
    AND ${visible()} AND m.seq>COALESCE((SELECT upto_seq FROM v2_reads r WHERE r.conversation_id=m.conversation_id AND r.reader=v.owner),0)
    AND NOT EXISTS(SELECT 1 FROM v2_blocks b WHERE b.owner=v.owner AND b.blocked=m.sender)`, now, job.device_id, job.message_id).first();
  if (!row) return { allow: false, quietUntil: 0 };
  const config = await notificationConfig(db, row.owner, job.device_id, row.conversation_id);
  if (!config.enabled || config.muted || config.mute_until_ms > now) return { allow: false, quietUntil: 0 };
  return { allow: true, quietUntil: quietUntil(config.quiet_hours, now) };
}
__name(notificationEligibility, "notificationEligibility");
async function notificationRoute(req, env, db, url, auth, deps) {
  const m = url.pathname.match(/^\/chat\/v2\/(me|devices\/([^/]+))(?:\/conversations\/([^/]+))?\/notifications$/);
  if (!m) return null;
  query(url, []);
  const scope = m[1] === "me" ? "account" : "device";
  const id = scope === "account" ? auth.subject : uuid(m[2]);
  if (scope === "device" && id !== auth.device_id) fail(403, "FORBIDDEN");
  const conv = m[3] ? uuid(m[3]) : "";
  if (conv) await conversation(db, conv, auth.subject);
  const get = /* @__PURE__ */ __name(async () => {
    const row = await statement(db, "SELECT CAST(revision AS TEXT) AS revision,config FROM v2_notification_preferences WHERE scope=? AND owner=? AND conversation_id=?", scope, id, conv).first();
    return { revision: row?.revision ?? "0", overrides: row ? JSON.parse(row.config) : {}, effective: await notificationConfig(db, auth.subject, auth.device_id, conv) };
  }, "get");
  if (req.method === "GET") {
    await noBody(req);
    return { ok: true, data: await get() };
  }
  const b = object(await body(req), ["expected_revision", "overrides"]);
  sequence(b.expected_revision);
  validateNotificationConfig(b.overrides);
  await atomic(db, [
    sessionGuard(db, auth, deps.now()),
    guard(db, "COALESCE((SELECT revision FROM v2_notification_preferences WHERE scope=? AND owner=? AND conversation_id=?),0)=CAST(? AS INTEGER)", scope, id, conv, b.expected_revision),
    statement(db, `INSERT INTO v2_notification_preferences(scope,owner,conversation_id,revision,config) VALUES(?,?,?,1,?)
      ON CONFLICT(scope,owner,conversation_id) DO UPDATE SET revision=revision+1,config=excluded.config`, scope, id, conv, JSON.stringify(b.overrides)),
    notifyAccount(db, auth.subject, "notification_preferences_changed", {}, deps.now())
  ]);
  return { ok: true, data: await get() };
}
__name(notificationRoute, "notificationRoute");

// src/bark.js
function barkEnabled(env) {
  if (env.BARK_ENABLED !== "true") return false;
  try {
    secret(env, "BARK_ENCRYPTION_KEY");
    return true;
  } catch {
    return false;
  }
}
__name(barkEnabled, "barkEnabled");
function barkOrigin(raw, env) {
  text(raw, 256);
  let u;
  try {
    u = new URL(raw);
  } catch {
    fail(400, "INVALID_REQUEST");
  }
  if (u.protocol !== "https:" || u.username || u.password || u.search || u.hash || u.pathname !== "" && u.pathname !== "/" || u.port || raw.includes("\\")) fail(400, "INVALID_REQUEST");
  const origin = u.origin;
  if (origin !== "https://api.day.app") fail(400, "BARK_ORIGIN_NOT_ALLOWED");
  return origin;
}
__name(barkOrigin, "barkOrigin");
async function aes(env) {
  return crypto.subtle.importKey("raw", secret(env, "BARK_ENCRYPTION_KEY"), "AES-GCM", false, ["encrypt", "decrypt"]);
}
__name(aes, "aes");
function bindingAAD(device, origin, version) {
  return new TextEncoder().encode(JSON.stringify([device, origin, version]));
}
__name(bindingAAD, "bindingAAD");
async function encryptKey(env, device, origin, version, key2) {
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const cipher = await crypto.subtle.encrypt({
    name: "AES-GCM",
    iv: nonce,
    additionalData: bindingAAD(device, origin, version)
  }, await aes(env), new TextEncoder().encode(key2));
  return `${base64(nonce)}:${base64(cipher)}`;
}
__name(encryptKey, "encryptKey");
async function decryptKey(env, row) {
  const [nonce, cipher] = row.encrypted_key.split(":");
  const data = await crypto.subtle.decrypt({
    name: "AES-GCM",
    iv: unbase64(nonce, 12),
    additionalData: bindingAAD(row.device_id, row.server_url, row.version)
  }, await aes(env), unbase64(cipher, 2048));
  return new TextDecoder().decode(data);
}
__name(decryptKey, "decryptKey");
async function barkRoute(req, env, db, url, auth, deps) {
  if (!barkEnabled(env)) fail(503, "BARK_NOT_CONFIGURED");
  query(url, req.method === "DELETE" ? ["expected_revision"] : []);
  const m = url.pathname.match(/^\/chat\/v2\/devices\/([^/]+)\/bark(?:\/(test|status))?$/);
  const id = uuid(m[1]);
  if (id !== auth.device_id) fail(403, "FORBIDDEN");
  const now = deps.now();
  const sg = sessionGuard(db, auth, now);
  if (!m[2] && req.method === "GET") {
    await noBody(req);
    const row = await statement(db, "SELECT * FROM v2_bark WHERE device_id=?", id).first();
    return { ok: true, data: {
      server_url: "https://api.day.app",
      enabled: !!row?.enabled,
      key_configured: !!row,
      device_key_masked: row ? `\u2022\u2022\u2022\u2022${(await decryptKey(env, row)).slice(-4)}` : null,
      revision: String(row?.revision ?? 0)
    } };
  }
  if (!m[2] && req.method === "PUT") {
    const b = object(await body(req), ["enabled"], ["server_url", "device_key", "expected_revision"]);
    if (typeof b.enabled !== "boolean") fail(400, "INVALID_REQUEST");
    const old = await statement(db, "SELECT * FROM v2_bark WHERE device_id=?", id).first();
    if (old && b.expected_revision === void 0) fail(428, "PRECONDITION_REQUIRED");
    const revision = sequence(b.expected_revision ?? "0");
    const deviceKey = b.device_key ?? (old ? await decryptKey(env, old) : null);
    if (!/^[A-Za-z0-9_-]{1,512}$/.test(text(deviceKey, 512))) fail(400, "INVALID_REQUEST");
    const origin = barkOrigin(b.server_url ?? "https://api.day.app", env);
    const version = crypto.randomUUID();
    const ciphertext = await encryptKey(env, id, origin, version, deviceKey);
    await atomic(db, [
      sg,
      guard(db, "COALESCE((SELECT revision FROM v2_bark WHERE device_id=?),0)=CAST(? AS INTEGER)", id, revision),
      statement(db, "UPDATE v2_outbox SET state='cancelled',lease_token=NULL,lease_until=0 WHERE device_id=? AND state IN ('queued','leased')", id),
      statement(db, `INSERT INTO v2_bark(device_id,server_url,encrypted_key,version,enabled) VALUES(?,?,?,?,?)
        ON CONFLICT(device_id) DO UPDATE SET server_url=excluded.server_url,encrypted_key=excluded.encrypted_key,
        version=excluded.version,enabled=excluded.enabled,revision=revision+1,last_result='none',last_error=NULL,last_attempt=NULL`, id, origin, ciphertext, version, b.enabled ? 1 : 0)
    ]);
    return { ok: true, data: { enabled: b.enabled, server_url: origin, preview: "generic", revision: String(BigInt(revision) + 1n) } };
  }
  if (!m[2] && req.method === "DELETE") {
    await noBody(req);
    const existing = await statement(db, "SELECT revision FROM v2_bark WHERE device_id=?", id).first();
    if (!existing) return { ok: true };
    const revision = sequence(url.searchParams.get("expected_revision"));
    await atomic(db, [
      sg,
      guard(db, "EXISTS(SELECT 1 FROM v2_bark WHERE device_id=? AND revision=?)", id, revision),
      statement(db, "DELETE FROM v2_bark WHERE device_id=?", id),
      statement(db, "UPDATE v2_outbox SET state='cancelled',lease_token=NULL,lease_until=0 WHERE device_id=? AND state IN ('queued','leased')", id)
    ]);
    return { ok: true };
  }
  if (m[2] === "status" && req.method === "GET") {
    await noBody(req);
    const row = await statement(db, "SELECT enabled,last_attempt,last_result,last_error FROM v2_bark WHERE device_id=?", id).first();
    return { ok: true, data: {
      enabled: !!row?.enabled,
      last_attempt_at_ms: row?.last_attempt ?? null,
      last_result: row?.last_result ?? "none",
      last_error_code: row?.last_error ?? null
    } };
  }
  if (m[2] === "test" && req.method === "POST") {
    object(await body(req), []);
    await rate(db, `bark-test:${id}`, 3, now);
    const row = await statement(db, "SELECT version FROM v2_bark WHERE device_id=? AND enabled=1", id).first();
    if (!row) fail(409, "BARK_NOT_CONFIGURED");
    const notification = crypto.randomUUID();
    await atomic(db, [
      sg,
      statement(db, `INSERT INTO v2_outbox(notification_id,device_id,binding_version,next_at,created_at)
      SELECT ?,device_id,version,?,? FROM v2_bark WHERE device_id=? AND enabled=1 AND version=?`, notification, now, now, id, row.version),
      statement(db, "UPDATE v2_bark SET last_result='queued',last_error=NULL WHERE device_id=? AND version=?", id, row.version)
    ]);
    const job = await statement(db, "SELECT notification_id FROM v2_outbox WHERE notification_id=?", notification).first();
    if (!job) fail(409, "BARK_NOT_CONFIGURED");
    return { ok: true, data: { queued: true, notification_id: notification } };
  }
  fail(405, "METHOD_NOT_ALLOWED");
}
__name(barkRoute, "barkRoute");
async function drainBark(env, db, deps, maximum = 10) {
  if (!barkEnabled(env)) return;
  for (let i = 0; i < maximum; i++) {
    const now = deps.now();
    const lease = crypto.randomUUID();
    const job = await statement(db, `UPDATE v2_outbox SET state='leased',lease_token=?,lease_until=?,attempts=attempts+1
      WHERE notification_id=(SELECT notification_id FROM v2_outbox
      WHERE state IN ('queued','leased') AND next_at<=? AND lease_until<=? ORDER BY next_at,created_at LIMIT 1)
      RETURNING *`, lease, now + 3e4, now, now).first();
    if (!job) return;
    const binding = await statement(db, `SELECT b.*,d.revoked FROM v2_bark b JOIN v2_devices d ON d.device_id=b.device_id
      WHERE b.device_id=? AND b.enabled=1 AND b.version=?`, job.device_id, job.binding_version).first();
    const eligibility = await notificationEligibility(db, job, now);
    if (!binding || binding.revoked || !eligibility.allow || now - job.created_at >= DAY) {
      await db.batch([
        statement(db, "UPDATE v2_outbox SET state='cancelled',lease_until=0 WHERE notification_id=? AND lease_token=?", job.notification_id, lease),
        statement(db, "DELETE FROM v2_notification_summaries WHERE notification_id=?", job.notification_id)
      ]);
      continue;
    }
    if (eligibility.quietUntil > now) {
      await statement(db, `UPDATE v2_outbox SET state='queued',quiet_summary=1,quiet_until=?,next_at=?,lease_until=0,lease_token=NULL,
        attempts=MAX(attempts-1,0) WHERE notification_id=? AND lease_token=?`, eligibility.quietUntil, eligibility.quietUntil, job.notification_id, lease).run();
      continue;
    }
    if (job.message_id) {
      const end = job.quiet_until || Math.floor(now / 1e4) * 1e4 + 1e4;
      await statement(db, "INSERT INTO v2_notification_summaries(device_id,window_end,notification_id) VALUES(?,?,?) ON CONFLICT(device_id,window_end) DO NOTHING", job.device_id, end, job.notification_id).run();
      const winner = await statement(db, "SELECT notification_id FROM v2_notification_summaries WHERE device_id=? AND window_end=?", job.device_id, end).first();
      if (winner.notification_id !== job.notification_id) {
        await statement(db, "UPDATE v2_outbox SET state='cancelled',lease_until=0 WHERE notification_id=? AND lease_token=?", job.notification_id, lease).run();
        continue;
      }
      await statement(db, "UPDATE v2_outbox SET quiet_until=? WHERE notification_id=? AND lease_token=?", end, job.notification_id, lease).run();
    }
    let result = "failed";
    let error = "NETWORK_ERROR";
    let retry = true;
    let retryAfter = 0;
    try {
      const origin = barkOrigin(binding.server_url, env);
      const deviceKey = await decryptKey(env, binding);
      const payload = {
        device_key: deviceKey,
        title: "\u661F\u706F\u4E91\u6D6A",
        body: job.message_id ? job.quiet_summary ? "\u4F60\u6709\u672A\u8BFB\u79C1\u804A\uFF0C\u8BF7\u6253\u5F00\u5E94\u7528\u67E5\u770B" : "\u6536\u5230\u65B0\u79C1\u804A\uFF0C\u8BF7\u6253\u5F00\u5E94\u7528\u67E5\u770B" : "\u79C1\u804A\u63D0\u9192\u6D4B\u8BD5",
        group: "starwave-chat",
        isArchive: 0
      };
      const request = new Request(`${origin}/push`, {
        method: "POST",
        redirect: "manual",
        signal: AbortSignal.timeout(8e3),
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      const r = await deps.fetch(request);
      const ra = r.headers.get("Retry-After");
      if (ra) retryAfter = /^\d+$/.test(ra) ? Number(ra) * 1e3 : Math.max(0, Date.parse(ra) - now) || 0;
      if (r.status === 429) error = "RATE_LIMITED";
      else if (r.status >= 500) error = "UPSTREAM_REJECTED";
      else if (!r.ok) {
        error = r.status === 400 || r.status === 401 ? "INVALID_KEY" : "UPSTREAM_REJECTED";
        retry = false;
      } else {
        const data = await r.json();
        if (data.code === 200) {
          result = "accepted";
          error = null;
          retry = false;
        } else {
          error = data.code === 429 ? "RATE_LIMITED" : "UPSTREAM_REJECTED";
          retry = data.code === 429 || data.code >= 500;
        }
      }
    } catch {
    }
    const delay = Math.max(retryAfter, 1e3 * 2 ** (job.attempts - 1) * (1 + deps.random() * 0.25));
    const next = now + delay;
    const again = result !== "accepted" && retry && job.attempts < 6 && next < job.created_at + DAY;
    await atomic(db, [
      statement(
        db,
        `UPDATE v2_outbox SET state=?,error=?,next_at=?,lease_until=0 WHERE notification_id=? AND lease_token=?`,
        again ? "queued" : result,
        error,
        next,
        job.notification_id,
        lease
      ),
      statement(
        db,
        `UPDATE v2_bark SET last_attempt=?,last_result=?,last_error=? WHERE device_id=? AND version=?
        AND EXISTS(SELECT 1 FROM v2_outbox WHERE notification_id=? AND lease_token=?)`,
        now,
        again ? "queued" : result,
        error,
        job.device_id,
        job.binding_version,
        job.notification_id,
        lease
      )
    ]);
  }
}
__name(drainBark, "drainBark");
async function cleanup(db, now) {
  await db.batch([
    statement(db, `UPDATE v2_devices SET floor_seq=MAX(floor_seq,COALESCE((SELECT MAX(seq) FROM v2_events e
      WHERE e.device_id=v2_devices.device_id AND e.expires_at<=?),0))`, now),
    statement(db, "DELETE FROM v2_events WHERE seq IN (SELECT seq FROM v2_events WHERE expires_at<=? LIMIT 1000)", now),
    statement(db, "DELETE FROM v2_sessions WHERE expires_at<=?", now),
    statement(db, "DELETE FROM v2_rate_limits WHERE window<?", Math.floor(now / 6e4) - 1440),
    statement(db, "DELETE FROM messages WHERE seq IN(SELECT seq FROM messages WHERE created_at<=? LIMIT 1000)", now - KEEP_FOREVER),
    statement(db, "DELETE FROM v2_notification_summaries WHERE window_end<?", now - 7 * DAY),
    statement(db, `DELETE FROM v2_outbox WHERE notification_id IN (SELECT notification_id FROM v2_outbox
      WHERE state NOT IN ('queued','leased') AND created_at<? LIMIT 1000)`, now - 7 * DAY),
    statement(db, `DELETE FROM v2_deliveries WHERE message_id IN (SELECT message_id FROM v2_messages WHERE expires_at<=?)`, now),
    statement(db, `DELETE FROM v2_keys WHERE revision<(SELECT key_revision FROM v2_devices WHERE device_id=v2_keys.device_id)
      AND NOT EXISTS(SELECT 1 FROM v2_events WHERE expires_at>? AND type='message' AND
      (json_extract(payload,'$.envelope.sender_key_id')=v2_keys.key_id OR
      json_extract(payload,'$.envelope.recipient_key_id')=v2_keys.key_id OR
      json_extract(payload,'$.envelope.own_key_id')=v2_keys.key_id))`, now)
  ]);
}
__name(cleanup, "cleanup");

// src/recovery.js
var CHUNK = 1048576;
function recoveryLimits(env) {
  const value = /* @__PURE__ */ __name((key2, fallback) => {
    const n = Number(env[key2] ?? fallback);
    if (!Number.isSafeInteger(n) || n < CHUNK) fail(503, "NOT_CONFIGURED");
    return n;
  }, "value");
  const pack = value("HISTORY_MAX_PACK_BYTES", 100 * CHUNK), account = value("HISTORY_ACCOUNT_BYTES", 500 * CHUNK);
  const uploads = Number(env.HISTORY_MAX_UPLOADS ?? 2);
  if (pack > account || pack > 1024 * CHUNK || !Number.isInteger(uploads) || uploads < 1 || uploads > 10) fail(503, "NOT_CONFIGURED");
  return { chunk_bytes: CHUNK, max_pack_bytes: pack, account_bytes: account, max_uploads: uploads, backup_days: 30, transfer_hours: 24 };
}
__name(recoveryLimits, "recoveryLimits");
var publicPack = /* @__PURE__ */ __name((p) => ({
  pack_id: p.pack_id,
  kind: p.kind,
  state: p.state,
  revision: String(p.revision),
  source_device: p.source_device,
  target_device: p.target_device,
  expected_bytes: p.expected_bytes,
  chunk_count: p.chunk_count,
  recovery_key_id: p.recovery_key_id,
  target_public_key: p.target_public_key,
  source_public_key: p.source_public_key,
  approval_proof: p.approval_proof,
  created_at_ms: p.created_at,
  expires_at_ms: p.expires_at,
  approval_expires_at_ms: p.approval_expires_at
}), "publicPack");
async function owned(db, id, kind, auth, now, allowExpired = false) {
  const p = await statement(db, "SELECT * FROM v2_recovery_packs WHERE pack_id=?", id).first();
  if (!p || p.kind !== kind) fail(404, "PACK_NOT_FOUND");
  if (p.owner !== auth.subject) fail(403, "FORBIDDEN");
  if (kind === "transfer" && ![p.source_device, p.target_device].includes(auth.device_id)) {
    if (p.state !== "awaiting_approval" || !await statement(db, "SELECT 1 FROM v2_transfer_candidates WHERE pack_id=? AND device_id=?", id, auth.device_id).first()) fail(403, "FORBIDDEN");
  }
  if (!allowExpired && (p.expires_at <= now || p.state === "awaiting_approval" && p.approval_expires_at <= now || ["cancelled", "expired", "deleting", "completed"].includes(p.state))) fail(410, "PACK_EXPIRED");
  return p;
}
__name(owned, "owned");
function sizes(expected, count, limits) {
  if (!Number.isSafeInteger(expected) || expected < 29 || expected > limits.max_pack_bytes || !Number.isInteger(count) || count !== Math.ceil(expected / CHUNK)) fail(400, "INVALID_REQUEST");
}
__name(sizes, "sizes");
function packGuard(db, p, now, state2 = "uploading") {
  return guard(db, `EXISTS(SELECT 1 FROM v2_recovery_packs WHERE pack_id=? AND revision=? AND state=? AND expires_at>?)`, p.pack_id, p.revision, state2, now);
}
__name(packGuard, "packGuard");
async function binary(req, max) {
  if ((req.headers.get("Content-Type") || "").split(";")[0] !== "application/octet-stream" || !req.body) fail(415, "INVALID_REQUEST");
  const reader = req.body.getReader();
  const chunks = [];
  let n = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      n += value.length;
      if (n > max) {
        await reader.cancel();
        fail(413, "PAYLOAD_TOO_LARGE");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const out = new Uint8Array(n);
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.length;
  }
  return out;
}
__name(binary, "binary");
async function filters(req, env, db, url, auth) {
  query(url, ["cursor", "limit"]);
  await noBody(req);
  const limit = Number(url.searchParams.get("limit") ?? "200");
  if (!Number.isInteger(limit) || limit < 1 || limit > 500) fail(400, "INVALID_REQUEST");
  const revision = String((await statement(db, "SELECT revision FROM v2_filter_clock WHERE owner=?", auth.subject).first())?.revision ?? 0);
  let after = "";
  if (url.searchParams.has("cursor")) {
    const c = await readOpaque(env, url.searchParams.get("cursor"));
    if (c.scope !== "history_filters" || c.owner !== auth.subject) fail(403, "FORBIDDEN");
    if (c.revision !== revision) fail(409, "FILTER_CHANGED");
    after = text(c.after, 256);
  }
  const list = await rows(statement(db, `WITH f AS(
    SELECT 'hidden:'||message_id AS key,'hidden' AS type,message_id AS target,NULL AS through_seq FROM v2_hidden WHERE owner=?
    UNION ALL SELECT 'clear:'||conversation_id,'clear',conversation_id,CAST(clear_seq AS TEXT) FROM v2_conversation_state WHERE owner=? AND clear_seq>0
    UNION ALL SELECT 'recall:'||message_id,'recall',message_id,NULL FROM v2_messages WHERE recalled_at IS NOT NULL AND(sender=? OR recipient=?))
    SELECT * FROM f WHERE key>? ORDER BY key LIMIT ?`, auth.subject, auth.subject, auth.subject, auth.subject, after, limit + 1));
  const current = String((await statement(db, "SELECT revision FROM v2_filter_clock WHERE owner=?", auth.subject).first())?.revision ?? 0);
  if (current !== revision) fail(409, "FILTER_CHANGED");
  const page = list.slice(0, limit);
  return { ok: true, data: {
    revision,
    filters: page.map(({ key: key2, ...r }) => r),
    has_more: list.length > limit,
    next_cursor: list.length > limit ? await signOpaque(env, { scope: "history_filters", owner: auth.subject, revision, after: page.at(-1).key }) : null
  } };
}
__name(filters, "filters");
async function recoveryRoute(req, env, db, url, auth, deps) {
  if (!url.pathname.startsWith("/chat/v2/history/")) return null;
  active(auth);
  const now = deps.now();
  const sg = /* @__PURE__ */ __name(() => sessionGuard(db, auth, now), "sg");
  if (url.pathname === "/chat/v2/history/filters") return filters(req, env, db, url, auth);
  if (!env.HISTORY_BUCKET?.put) fail(503, "HISTORY_NOT_CONFIGURED");
  const limits = recoveryLimits(env);
  const bucket = env.HISTORY_BUCKET;
  if (url.pathname === "/chat/v2/history/quota") {
    query(url, []);
    await noBody(req);
    const used = await statement(db, "SELECT COALESCE(SUM(expected_bytes),0) AS reserved_bytes FROM v2_recovery_packs WHERE owner=? AND gc_done=0", auth.subject).first();
    return { ok: true, data: { ...limits, ...used } };
  }
  const m = url.pathname.match(/^\/chat\/v2\/history\/(backups|transfers)(?:\/([^/]+)(?:\/(approve|complete|ack|chunks)(?:\/([0-9]+))?)?)?$/);
  if (!m) fail(404, "NOT_FOUND");
  const kind = m[1] === "backups" ? "backup" : "transfer";
  if (!m[2] && req.method === "GET") {
    query(url, ["before", "limit"]);
    await noBody(req);
    const before = Number(url.searchParams.get("before") ?? Number.MAX_SAFE_INTEGER);
    const limit = Number(url.searchParams.get("limit") ?? "50");
    if (!Number.isSafeInteger(before) || !Number.isInteger(limit) || limit < 1 || limit > 100) fail(400, "INVALID_REQUEST");
    const list = await rows(statement(db, `SELECT rowid AS position,* FROM v2_recovery_packs WHERE owner=? AND kind=? AND rowid<?
      AND (?='backup' OR source_device=? OR target_device=? OR pack_id IN(SELECT pack_id FROM v2_transfer_candidates WHERE device_id=?))
      ORDER BY rowid DESC LIMIT ?`, auth.subject, kind, before, kind, auth.device_id, auth.device_id, auth.device_id, limit + 1));
    const page = list.slice(0, limit);
    return { ok: true, data: { packs: page.map(publicPack), has_more: list.length > limit, next_before: page.at(-1)?.position ?? null } };
  }
  if (!m[2] && req.method === "POST") {
    query(url, []);
    await rate(db, `history-create:${auth.subject}`, 10, now);
    const b = object(await body(req), kind === "backup" ? ["pack_id", "expected_bytes", "chunk_count", "recovery_key_id"] : ["pack_id", "target_public_key"], kind === "transfer" ? ["max_bytes"] : []);
    const id2 = uuid(b.pack_id);
    const creationHash = await hash(canonical(b));
    const prior = await statement(db, "SELECT * FROM v2_recovery_packs WHERE pack_id=?", id2).first();
    if (prior) {
      if (prior.owner !== auth.subject || prior.creator_device !== auth.device_id || prior.kind !== kind || prior.creation_hash !== creationHash) fail(409, "IDEMPOTENCY_CONFLICT");
      return { ok: true, data: publicPack(prior) };
    }
    const expected = kind === "backup" ? b.expected_bytes : b.max_bytes ?? limits.max_pack_bytes;
    const count = kind === "backup" ? b.chunk_count : 0;
    if (kind === "backup") {
      sizes(expected, count, limits);
      uuid(b.recovery_key_id);
    } else {
      if (!Number.isSafeInteger(expected) || expected < 29 || expected > limits.max_pack_bytes) fail(400, "INVALID_REQUEST");
      await publicKey("X25519", b.target_public_key);
    }
    const state2 = kind === "backup" ? "uploading" : "awaiting_approval";
    await atomic(db, [
      sg(),
      guard(db, `(SELECT COALESCE(SUM(expected_bytes),0) FROM v2_recovery_packs WHERE owner=? AND gc_done=0)+?<=?
      AND (SELECT COUNT(*) FROM v2_recovery_packs WHERE owner=? AND state IN('uploading','awaiting_approval') AND gc_done=0)<?`, auth.subject, expected, limits.account_bytes, auth.subject, limits.max_uploads),
      statement(
        db,
        `INSERT INTO v2_recovery_packs(pack_id,owner,kind,source_device,target_device,creator_device,state,expected_bytes,chunk_count,
        recovery_key_id,target_public_key,created_at,expires_at,approval_expires_at,creation_hash)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
        id2,
        auth.subject,
        kind,
        kind === "backup" ? auth.device_id : null,
        kind === "transfer" ? auth.device_id : null,
        auth.device_id,
        state2,
        expected,
        count,
        b.recovery_key_id ?? null,
        b.target_public_key ?? null,
        now,
        now + (kind === "backup" ? KEEP_FOREVER : DAY),
        kind === "transfer" ? now + 6e5 : null,
        creationHash
      ),
      ...kind === "transfer" ? [statement(db, `INSERT INTO v2_transfer_candidates(pack_id,device_id,key_revision)
        SELECT ?,device_id,key_revision FROM v2_devices WHERE subject=? AND active=1 AND revoked=0 AND device_id<>?`, id2, auth.subject, auth.device_id)] : []
    ], "QUOTA_OR_UPLOAD_LIMIT");
    return { ok: true, data: publicPack(await statement(db, "SELECT * FROM v2_recovery_packs WHERE pack_id=?", id2).first()) };
  }
  const id = uuid(m[2]);
  const action = m[3];
  query(url, action === "chunks" && req.method === "GET" ? [] : req.method === "DELETE" ? ["expected_revision"] : []);
  const p = await owned(db, id, kind, auth, now, req.method === "DELETE" || action === "ack");
  if (!action && req.method === "GET") {
    await noBody(req);
    const chunks = await rows(statement(db, "SELECT chunk_index,bytes,digest,state FROM v2_recovery_chunks WHERE pack_id=? ORDER BY chunk_index", id));
    return { ok: true, data: { ...publicPack(p), chunks, manifest: p.state === "ready" ? p.manifest : null, restore_filters: "/chat/v2/history/filters" } };
  }
  if (!action && req.method === "DELETE") {
    await noBody(req);
    if (["cancelled", "expired", "completed", "deleting"].includes(p.state)) return { ok: true };
    const revision = sequence(url.searchParams.get("expected_revision"));
    await atomic(db, [
      sg(),
      guard(db, "EXISTS(SELECT 1 FROM v2_recovery_packs WHERE pack_id=? AND revision=?)", id, revision),
      statement(db, "UPDATE v2_recovery_packs SET state='deleting',delete_reason='cancelled',revision=revision+1 WHERE pack_id=?", id)
    ]);
    return { ok: true, data: { state: "deleting" } };
  }
  if (action === "approve" && kind === "transfer" && req.method === "POST") {
    const b = object(await body(req), ["expected_revision", "expected_bytes", "chunk_count", "source_public_key", "approval_proof"]);
    sequence(b.expected_revision);
    sizes(b.expected_bytes, b.chunk_count, limits);
    if (p.state !== "awaiting_approval" || p.target_device === auth.device_id || p.approval_expires_at <= now) fail(409, "APPROVAL_UNAVAILABLE");
    if (b.expected_bytes > p.expected_bytes) fail(409, "QUOTA_OR_UPLOAD_LIMIT");
    await publicKey("X25519", b.source_public_key);
    if (unbase64(b.approval_proof, 32).length !== 32) fail(400, "INVALID_REQUEST");
    await atomic(db, [
      sg(),
      guard(db, `EXISTS(SELECT 1 FROM v2_recovery_packs WHERE pack_id=? AND revision=? AND state='awaiting_approval' AND approval_expires_at>?)
      AND EXISTS(SELECT 1 FROM v2_transfer_candidates c JOIN v2_devices d ON d.device_id=c.device_id AND d.key_revision=c.key_revision WHERE c.pack_id=? AND c.device_id=? AND d.revoked=0 AND d.active=1)
      AND EXISTS(SELECT 1 FROM v2_devices WHERE device_id=? AND active=1 AND revoked=0)`, id, b.expected_revision, now, id, auth.device_id, p.target_device),
      statement(
        db,
        `UPDATE v2_recovery_packs SET source_device=?,state='uploading',revision=revision+1,expected_bytes=?,chunk_count=?,source_public_key=?,approval_proof=? WHERE pack_id=?`,
        auth.device_id,
        b.expected_bytes,
        b.chunk_count,
        b.source_public_key,
        b.approval_proof,
        id
      )
    ]);
    return { ok: true, data: publicPack(await statement(db, "SELECT * FROM v2_recovery_packs WHERE pack_id=?", id).first()) };
  }
  if (action === "chunks" && m[4] !== void 0) {
    const index = Number(m[4]);
    if (!Number.isSafeInteger(index) || index < 0 || index >= p.chunk_count) fail(400, "INVALID_REQUEST");
    if (req.method === "GET") {
      await noBody(req);
      if (p.state !== "ready" || kind === "transfer" && p.target_device !== auth.device_id) fail(409, "PACK_NOT_READY");
      const chunk = await statement(db, "SELECT * FROM v2_recovery_chunks WHERE pack_id=? AND chunk_index=? AND state='ready'", id, index).first();
      if (!chunk) fail(404, "CHUNK_NOT_FOUND");
      const stored = await bucket.get(chunk.object_key);
      if (!stored) fail(503, "OBJECT_UNAVAILABLE");
      return new Response(stored.body, { headers: { "Content-Type": "application/octet-stream", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*", "Access-Control-Expose-Headers": "X-Chunk-SHA256", "X-Chunk-SHA256": chunk.digest } });
    }
    if (p.state !== "uploading" || p.source_device !== auth.device_id) fail(403, "FORBIDDEN");
    await rate(db, `history-upload:${auth.subject}`, 120, now);
    const expected = index === p.chunk_count - 1 ? p.expected_bytes - CHUNK * index : CHUNK;
    const bytes = await binary(req, CHUNK);
    if (bytes.length !== expected) fail(400, "CHUNK_SIZE_MISMATCH");
    const digest = base64(await crypto.subtle.digest("SHA-256", bytes));
    if (req.headers.get("X-Chunk-SHA256") !== digest) fail(400, "DIGEST_MISMATCH");
    const existing = await statement(db, "SELECT * FROM v2_recovery_chunks WHERE pack_id=? AND chunk_index=?", id, index).first();
    if (existing && (existing.digest !== digest || existing.bytes !== bytes.length)) fail(409, "IDEMPOTENCY_CONFLICT");
    if (existing?.state === "ready") return { ok: true, data: { chunk_index: index, digest } };
    const key2 = `history/${id}/${index}`;
    await atomic(db, [sg(), packGuard(db, p, now), statement(db, `INSERT INTO v2_recovery_chunks(pack_id,chunk_index,bytes,digest,object_key,state) VALUES(?,?,?,?,?,'writing')
      ON CONFLICT(pack_id,chunk_index) DO NOTHING`, id, index, bytes.length, digest, key2)]);
    const reserved = await statement(db, "SELECT digest FROM v2_recovery_chunks WHERE pack_id=? AND chunk_index=?", id, index).first();
    if (reserved.digest !== digest) fail(409, "IDEMPOTENCY_CONFLICT");
    try {
      await bucket.put(key2, bytes, { httpMetadata: { contentType: "application/octet-stream" }, customMetadata: { digest } });
      await atomic(db, [sg(), packGuard(db, p, now), statement(db, "UPDATE v2_recovery_chunks SET state='ready' WHERE pack_id=? AND chunk_index=? AND digest=?", id, index, digest)]);
    } catch (e) {
      const current = await statement(db, "SELECT state FROM v2_recovery_packs WHERE pack_id=?", id).first();
      if (current && !["uploading", "ready"].includes(current.state)) {
        try {
          await bucket.delete(key2);
        } catch {
          await db.batch([
            statement(db, `INSERT INTO v2_recovery_chunks(pack_id,chunk_index,bytes,digest,object_key,state) VALUES(?,?,?,?,?,'writing') ON CONFLICT(pack_id,chunk_index) DO NOTHING`, id, index, bytes.length, digest, key2),
            statement(db, "UPDATE v2_recovery_packs SET gc_done=0 WHERE pack_id=?", id)
          ]);
        }
      }
      throw e;
    }
    return { ok: true, data: { chunk_index: index, digest } };
  }
  if (action === "complete" && req.method === "POST") {
    const b = object(await body(req), ["expected_revision", "manifest"]);
    sequence(b.expected_revision);
    text(b.manifest, 65536);
    if (new TextEncoder().encode(b.manifest).length > 65536) fail(413, "PAYLOAD_TOO_LARGE");
    let manifest;
    try {
      manifest = JSON.parse(b.manifest);
    } catch {
      fail(400, "INVALID_MANIFEST");
    }
    object(manifest, ["format", "salt", "sealed_manifest"]);
    if (manifest.format !== "nu-chat-history/1" || unbase64(manifest.salt, 32).length !== 32 || unbase64(manifest.sealed_manifest, 49152).length < 28) fail(400, "INVALID_MANIFEST");
    if (p.source_device !== auth.device_id) fail(403, "FORBIDDEN");
    if (p.state === "ready" && p.manifest === b.manifest) return { ok: true, data: publicPack(p) };
    await atomic(db, [
      sg(),
      guard(db, `EXISTS(SELECT 1 FROM v2_recovery_packs WHERE pack_id=? AND revision=? AND state='uploading' AND expires_at>?)
      AND (SELECT COUNT(*) FROM v2_recovery_chunks WHERE pack_id=? AND state='ready')=?
      AND (SELECT SUM(bytes) FROM v2_recovery_chunks WHERE pack_id=? AND state='ready')=?`, id, b.expected_revision, now, id, p.chunk_count, id, p.expected_bytes),
      statement(db, "UPDATE v2_recovery_packs SET state='ready',manifest=?,revision=revision+1 WHERE pack_id=?", b.manifest, id)
    ]);
    return { ok: true, data: publicPack(await statement(db, "SELECT * FROM v2_recovery_packs WHERE pack_id=?", id).first()) };
  }
  if (action === "ack" && kind === "transfer" && req.method === "POST") {
    const b = object(await body(req), ["expected_revision", "filter_revision"]);
    sequence(b.expected_revision);
    sequence(b.filter_revision);
    if (p.target_device !== auth.device_id) fail(403, "FORBIDDEN");
    if (p.state === "completed") return { ok: true, data: { state: "completed" } };
    if (p.expires_at <= now) fail(410, "PACK_EXPIRED");
    const current = String((await statement(db, "SELECT revision FROM v2_filter_clock WHERE owner=?", auth.subject).first())?.revision ?? 0);
    if (current !== b.filter_revision) fail(409, "FILTER_CHANGED");
    await atomic(db, [
      sg(),
      guard(db, `EXISTS(SELECT 1 FROM v2_recovery_packs WHERE pack_id=? AND revision=? AND state='ready')
      AND COALESCE((SELECT revision FROM v2_filter_clock WHERE owner=?),0)=CAST(? AS INTEGER)`, id, b.expected_revision, auth.subject, b.filter_revision),
      statement(db, "UPDATE v2_recovery_packs SET state='completed',completed_at=?,revision=revision+1 WHERE pack_id=?", now, id)
    ]);
    return { ok: true, data: { state: "completed" } };
  }
  fail(405, "METHOD_NOT_ALLOWED");
}
__name(recoveryRoute, "recoveryRoute");
async function sweepRecovery(env, db, now) {
  if (!env.HISTORY_BUCKET?.delete) return;
  const packs = await rows(statement(db, `SELECT * FROM v2_recovery_packs WHERE gc_done=0 AND
    (state IN('deleting','completed') OR expires_at<=? OR (state='awaiting_approval' AND approval_expires_at<=?)) LIMIT 20`, now, now));
  for (const p of packs) {
    await statement(db, `UPDATE v2_recovery_packs SET state=CASE WHEN state='completed' THEN state ELSE 'deleting' END,
      delete_reason=CASE WHEN expires_at<=? OR (state='awaiting_approval' AND approval_expires_at<=?) THEN 'expired' ELSE delete_reason END,
      revision=revision+1 WHERE pack_id=? AND gc_done=0`, now, now, p.pack_id).run();
    const chunks = await rows(statement(db, "SELECT object_key FROM v2_recovery_chunks WHERE pack_id=?", p.pack_id));
    for (let i = 0; i < chunks.length; i += 100) await env.HISTORY_BUCKET.delete(chunks.slice(i, i + 100).map((c) => c.object_key));
    await db.batch([
      statement(db, "DELETE FROM v2_recovery_chunks WHERE pack_id=?", p.pack_id),
      statement(db, `UPDATE v2_recovery_packs SET gc_done=1,manifest=NULL,state=CASE WHEN state='completed' THEN state ELSE delete_reason END WHERE pack_id=?`, p.pack_id),
      statement(db, "DELETE FROM v2_transfer_candidates WHERE pack_id=?", p.pack_id)
    ]);
  }
  if (env.HISTORY_BUCKET.list) {
    const state2 = await statement(db, "SELECT cursor FROM v2_blob_gc_state WHERE id=1").first();
    const listed = await env.HISTORY_BUCKET.list({ prefix: "history/", limit: 1e3, ...state2?.cursor ? { cursor: state2.cursor } : {} });
    const keys = listed.objects.map((o) => o.key);
    if (keys.length) {
      const tracked = await rows(statement(db, `SELECT c.object_key FROM v2_recovery_chunks c JOIN v2_recovery_packs p ON p.pack_id=c.pack_id
        WHERE c.object_key IN(SELECT value FROM json_each(?)) AND p.gc_done=0 AND p.state IN('uploading','ready') AND p.expires_at>?`, JSON.stringify(keys), now));
      const keep = new Set(tracked.map((r) => r.object_key));
      const orphan = keys.filter((k) => !keep.has(k));
      for (let i = 0; i < orphan.length; i += 100) await env.HISTORY_BUCKET.delete(orphan.slice(i, i + 100));
    }
    await statement(db, "UPDATE v2_blob_gc_state SET cursor=? WHERE id=1", listed.truncated ? listed.cursor : null).run();
  }
}
__name(sweepRecovery, "sweepRecovery");

// src/android.js
async function legacyAlgorithm(key2) {
  for (const algorithm of ["X25519", "P256"]) try {
    await publicKey(algorithm, key2);
    return algorithm;
  } catch {
  }
  fail(409, "LEGACY_KEY_UNSUPPORTED");
}
__name(legacyAlgorithm, "legacyAlgorithm");
async function legacyPeer(db, name, algorithm) {
  const binding = await statement(db, `SELECT a.subject,b.*,d.active,d.revoked,d.key_revision AS current_revision
    FROM v2_accounts a JOIN v2_legacy_bindings b ON b.subject=a.subject JOIN v2_devices d ON d.device_id=b.device_id
    WHERE a.username=?`, name).first();
  if (binding?.enabled) {
    if (!binding.active || binding.revoked || binding.current_revision !== binding.key_revision) fail(409, "LEGACY_DEVICE_UNAVAILABLE");
    const key3 = await statement(db, "SELECT key_id,algorithm,public_key FROM v2_keys WHERE device_id=? AND revision=? AND algorithm=?", binding.device_id, binding.key_revision, algorithm ?? binding.default_algorithm).first();
    if (!key3) fail(409, "LEGACY_ALGORITHM_MISMATCH");
    return { ...key3, subject: binding.subject, device_id: binding.device_id };
  }
  const key2 = await statement(db, "SELECT public_key FROM users WHERE username=?", name).first();
  if (!key2) fail(404, "USER_NOT_FOUND");
  const found = await legacyAlgorithm(key2.public_key);
  if (algorithm && algorithm !== found) fail(409, "LEGACY_ALGORITHM_MISMATCH");
  const account = await statement(db, "SELECT subject FROM v2_accounts WHERE username=?", name).first();
  const owned2 = await statement(db, "SELECT subject FROM v2_legacy_users WHERE username=?", name).first();
  if (!account || owned2?.subject !== account.subject) fail(409, "LEGACY_IDENTITY_UNBOUND");
  return { public_key: key2.public_key, algorithm: found, subject: account.subject, device_id: null, key_id: null };
}
__name(legacyPeer, "legacyPeer");
async function legacySend(db, env, from, b, now, authGuard = null, sourceKeyId = null) {
  const msgId = b.msgId;
  const bodyHash = await hash(canonical(b));
  const old = await statement(db, `SELECT m.*,l.request_hash FROM messages m LEFT JOIN v2_legacy_link l ON l.legacy_seq=m.seq WHERE m.from_user=? AND m.msg_id=?`, from, msgId).first();
  if (old) {
    if (old.to_user !== b.to || old.cipher !== b.cipher || old.ts !== b.ts || old.request_hash && old.request_hash !== bodyHash) fail(409, "IDEMPOTENCY_CONFLICT");
    return { ok: true, duplicate: true, seq: old.seq };
  }
  const sender = await legacyPeer(db, from);
  await rate(db, `send:${sender.subject}`, 30, now);
  let selected = sender;
  if (sourceKeyId) {
    const key2 = await statement(db, "SELECT algorithm FROM v2_keys WHERE key_id=? AND device_id=?", sourceKeyId, sender.device_id).first();
    if (!key2) fail(409, "KEYSET_CHANGED");
    selected = await legacyPeer(db, from, key2.algorithm);
    if (selected.key_id !== sourceKeyId) fail(409, "KEYSET_CHANGED");
  }
  const recipient = await legacyPeer(db, b.to, selected.algorithm);
  if (b.recipient_public_key !== void 0 && b.recipient_public_key !== recipient.public_key) fail(409, "KEYSET_CHANGED");
  const algorithm = selected.algorithm;
  if (!selected.subject || !recipient.subject) {
    fail(409, "LEGACY_IDENTITY_UNBOUND");
  }
  const [a, z] = [selected.subject, recipient.subject].sort();
  if (a === z) fail(400, "INVALID_REQUEST");
  const id = crypto.randomUUID();
  const sourceDevice = selected.device_id ?? `legacy:${from}`;
  const stmts = [
    ...authGuard ? [authGuard] : [],
    deliveryGuard(db, selected.subject, recipient.subject),
    // Public-key selection and message commit share a transaction/CAS boundary.
    guard(db, `(NOT EXISTS(SELECT 1 FROM v2_legacy_bindings b JOIN v2_accounts a ON a.subject=b.subject WHERE a.username=? AND b.enabled=1)
      AND EXISTS(SELECT 1 FROM users WHERE username=? AND public_key=?)) OR EXISTS(
      SELECT 1 FROM v2_legacy_bindings b JOIN v2_keys k ON k.device_id=b.device_id AND k.revision=b.key_revision
      JOIN v2_accounts a ON a.subject=b.subject JOIN v2_devices d ON d.device_id=b.device_id
      WHERE a.username=? AND b.enabled=1 AND d.revoked=0 AND d.active=1 AND d.key_revision=b.key_revision AND k.public_key=?)`, from, from, selected.public_key, from, selected.public_key),
    guard(db, `(NOT EXISTS(SELECT 1 FROM v2_legacy_bindings b JOIN v2_accounts a ON a.subject=b.subject WHERE a.username=? AND b.enabled=1)
      AND EXISTS(SELECT 1 FROM users WHERE username=? AND public_key=?)) OR EXISTS(
      SELECT 1 FROM v2_legacy_bindings b JOIN v2_keys k ON k.device_id=b.device_id AND k.revision=b.key_revision
      JOIN v2_accounts a ON a.subject=b.subject JOIN v2_devices d ON d.device_id=b.device_id
      WHERE a.username=? AND b.enabled=1 AND d.revoked=0 AND d.active=1 AND d.key_revision=b.key_revision AND k.public_key=?)`, b.to, b.to, recipient.public_key, b.to, recipient.public_key),
    statement(db, `INSERT INTO messages(msg_id,from_user,to_user,cipher,ts,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(from_user,msg_id) DO NOTHING`, msgId, from, b.to, b.cipher, b.ts, now),
    guard(db, "EXISTS(SELECT 1 FROM messages WHERE from_user=? AND msg_id=? AND to_user=? AND cipher=? AND ts=?)", from, msgId, b.to, b.cipher, b.ts),
    statement(db, "INSERT INTO v2_conversations(conversation_id,a,b) VALUES(?,?,?) ON CONFLICT(a,b) DO NOTHING", crypto.randomUUID(), a, z),
    statement(db, `INSERT INTO v2_messages(message_id,client_msg_id,sender,sender_device,recipient,conversation_id,request_hash,accepted_at,expires_at)
      SELECT ?,?,?,?,?,conversation_id,?,?,? FROM v2_conversations WHERE a=? AND b=?
      ON CONFLICT(sender,sender_device,client_msg_id) DO NOTHING`, id, msgId, selected.subject, sourceDevice, recipient.subject, bodyHash, now, now + KEEP_FOREVER, a, z),
    guard(db, "EXISTS(SELECT 1 FROM v2_messages WHERE sender=? AND sender_device=? AND client_msg_id=? AND request_hash=?)", selected.subject, sourceDevice, msgId, bodyHash),
    statement(db, `INSERT INTO v2_legacy_link(legacy_seq,message_id,request_hash,algorithm,sender_public_key,recipient_public_key)
      SELECT l.seq,m.message_id,?,?,?,? FROM messages l JOIN v2_messages m ON m.sender=? AND m.sender_device=? AND m.client_msg_id=?
      WHERE l.from_user=? AND l.msg_id=? ON CONFLICT(legacy_seq) DO NOTHING`, bodyHash, algorithm, selected.public_key, recipient.public_key, selected.subject, sourceDevice, msgId, from, msgId)
  ];
  for (const owner of [selected.subject, recipient.subject]) stmts.push(statement(db, `INSERT INTO v2_copies(owner,message_id,expires_at)
    SELECT ?,m.message_id,MIN(m.expires_at,m.accepted_at+COALESCE((SELECT retention_days FROM v2_preferences WHERE subject=?),30)*?)
    FROM v2_messages m JOIN v2_legacy_link l ON l.message_id=m.message_id JOIN messages old ON old.seq=l.legacy_seq
    WHERE old.from_user=? AND old.msg_id=? ON CONFLICT(owner,message_id) DO NOTHING`, owner, owner, DAY, from, msgId));
  for (const target of [selected, recipient]) if (target.device_id) {
    stmts.push(statement(db, `INSERT INTO v2_events(device_id,dedup,type,message_id,payload,expires_at)
      SELECT ?,'msg:'||m.message_id,'message',m.message_id,json_object('type','message','message_id',m.message_id,
      'client_msg_id',m.client_msg_id,'conversation_id',m.conversation_id,'conversation_seq',CAST(m.seq AS TEXT),
      'sender_subject',m.sender,'sender_device_id',m.sender_device,'recipient_subject',m.recipient,'accepted_at_ms',m.accepted_at,
      'expires_at_ms',cp.expires_at,'envelope',json_object('protocol','nu-chat-v1','algorithm',l.algorithm,'cipher',old.cipher,
      'sender_public_key',l.sender_public_key,'recipient_public_key',l.recipient_public_key,'own_key_id',?)),cp.expires_at
      FROM v2_messages m JOIN v2_legacy_link l ON l.message_id=m.message_id JOIN messages old ON old.seq=l.legacy_seq
      JOIN v2_copies cp ON cp.message_id=m.message_id AND cp.owner=? WHERE old.from_user=? AND old.msg_id=?
      ON CONFLICT(device_id,dedup) DO NOTHING`, target.device_id, target.key_id, target.subject, from, msgId));
    if (target === recipient && barkEnabled(env)) stmts.push(statement(db, `INSERT INTO v2_outbox(notification_id,device_id,message_id,binding_version,next_at,created_at)
      SELECT ?,?,l.message_id,k.version,?,? FROM v2_legacy_link l JOIN messages old ON old.seq=l.legacy_seq
      JOIN v2_bark k ON k.device_id=? AND k.enabled=1 WHERE old.from_user=? AND old.msg_id=?
      ON CONFLICT(message_id,device_id) DO NOTHING`, crypto.randomUUID(), target.device_id, now, now, target.device_id, from, msgId));
  }
  await atomic(db, stmts, "DELIVERY_OR_KEY_CONFLICT");
  const result = await statement(db, "SELECT seq FROM messages WHERE from_user=? AND msg_id=?", from, msgId).first();
  const committed = await statement(db, "SELECT message_id FROM v2_legacy_link WHERE legacy_seq=?", result.seq).first();
  return { ok: true, duplicate: committed.message_id !== id, seq: result.seq };
}
__name(legacySend, "legacySend");
async function androidRoute(req, env, db, url, auth, deps) {
  const now = deps.now();
  const path = url.pathname;
  let match = path.match(/^\/chat\/v2\/devices\/([^/]+)\/legacy$/);
  if (match) {
    query(url, []);
    active(auth);
    const id = uuid(match[1]);
    if (id !== auth.device_id) fail(403, "FORBIDDEN");
    const current = await statement(db, "SELECT * FROM v2_legacy_bindings WHERE subject=?", auth.subject).first();
    if (req.method === "GET") {
      await noBody(req);
      return { ok: true, data: { enabled: !!current?.enabled, device_id: current?.device_id ?? null, revision: String(current?.revision ?? 0), primary_device_only: true } };
    }
    const b = object(await body(req), ["expected_revision", "key_revision"], ["default_algorithm"]);
    sequence(b.expected_revision);
    sequence(b.key_revision);
    const keys = await bundle(db, auth.subject);
    const device = keys.devices.find((d) => d.device_id === id);
    if (!device || device.key_revision !== b.key_revision) fail(409, "KEYSET_CHANGED");
    const algorithm = b.default_algorithm ?? "X25519";
    const key2 = device.keys.find((k) => k.algorithm === algorithm);
    if (!key2) fail(400, "UNSUPPORTED_ALGORITHM");
    if (current?.device_id === id && String(current.key_revision) === b.key_revision && current.default_algorithm === algorithm && (b.expected_revision === String(current.revision) || BigInt(b.expected_revision) + 1n === BigInt(current.revision)))
      return { ok: true, data: { enabled: true, device_id: id, revision: String(current.revision), primary_device_only: true } };
    if (b.expected_revision !== String(current?.revision ?? 0)) fail(409, "REVISION_CONFLICT");
    if (current && current.device_id !== id) fail(409, "LEGACY_IDENTITY_ALREADY_BOUND");
    const old = await statement(db, "SELECT public_key FROM users WHERE username=?", auth.username).first();
    if (!current && old && !device.keys.some((k) => k.public_key === old.public_key)) fail(409, "LEGACY_ACCOUNT_ALREADY_IN_USE");
    await atomic(db, [
      sessionGuard(db, auth, now),
      guard(db, `COALESCE((SELECT revision FROM v2_legacy_bindings WHERE subject=?),0)=CAST(? AS INTEGER)
      AND NOT EXISTS(SELECT 1 FROM users WHERE username=? AND public_key<>?)
      AND EXISTS(SELECT 1 FROM v2_devices WHERE device_id=? AND key_revision=? AND active=1 AND revoked=0)`, auth.subject, b.expected_revision, auth.username, old?.public_key ?? key2.public_key, id, b.key_revision),
      statement(db, `INSERT INTO v2_legacy_bindings(subject,device_id,key_revision,default_algorithm) VALUES(?,?,?,?)
        ON CONFLICT(subject) DO UPDATE SET key_revision=excluded.key_revision,default_algorithm=excluded.default_algorithm,revision=revision+1`, auth.subject, id, b.key_revision, algorithm),
      statement(db, "INSERT INTO users(username,public_key,updated_at) VALUES(?,?,?) ON CONFLICT(username) DO UPDATE SET public_key=excluded.public_key,updated_at=excluded.updated_at", auth.username, key2.public_key, now),
      statement(db, "INSERT INTO v2_legacy_users(username,subject) VALUES(?,?) ON CONFLICT(username) DO UPDATE SET subject=excluded.subject", auth.username, auth.subject)
    ]);
    return { ok: true, data: { enabled: true, device_id: id, revision: String(Number(current?.revision ?? 0) + 1), primary_device_only: true } };
  }
  match = path.match(/^\/chat\/v2\/users\/([^/]+)\/protocols$/);
  if (match) {
    query(url, []);
    await noBody(req);
    let name;
    try {
      name = decodeURIComponent(match[1]);
    } catch {
      fail(400, "INVALID_REQUEST");
    }
    username(name);
    const account = await statement(db, "SELECT subject FROM v2_accounts WHERE username=?", name).first();
    if (!account) fail(404, "USER_NOT_FOUND");
    const native = await bundle(db, account.subject);
    const keys = [];
    for (const alg of ["X25519", "P256"]) try {
      const key2 = await legacyPeer(db, name, alg);
      keys.push({ algorithm: key2.algorithm, public_key: key2.public_key });
    } catch (e) {
      if (!["USER_NOT_FOUND", "LEGACY_ALGORITHM_MISMATCH", "LEGACY_DEVICE_UNAVAILABLE", "LEGACY_IDENTITY_UNBOUND"].includes(e.code)) throw e;
    }
    const binding = await statement(db, "SELECT device_id FROM v2_legacy_bindings WHERE subject=?", account.subject).first();
    return { ok: true, data: {
      username: name,
      subject: account.subject,
      protocols: [...native.devices.length ? ["nu-chat-v2"] : [], ...keys.length ? ["nu-chat-v1"] : []],
      legacy_public_keys: keys,
      legacy_primary_device: binding?.device_id ?? null
    } };
  }
  if (path === "/chat/v2/legacy/messages") {
    query(url, []);
    active(auth);
    const b = object(await body(req), ["client_msg_id", "to_username", "cipher", "ts", "sender_key_id", "recipient_public_key"]);
    uuid(b.client_msg_id);
    uuid(b.sender_key_id);
    username(b.to_username);
    text(b.cipher, 24e3);
    if (!Number.isSafeInteger(b.ts) || b.ts < 0) fail(400, "INVALID_REQUEST");
    const parts = b.cipher.split(":");
    if (parts.length !== 2 || unbase64(parts[0], 12).length !== 12 || unbase64(parts[1], 16400).length < 16) fail(400, "INVALID_REQUEST");
    const binding = await statement(db, "SELECT device_id FROM v2_legacy_bindings WHERE subject=? AND enabled=1", auth.subject).first();
    if (binding?.device_id !== auth.device_id) fail(403, "LEGACY_PRIMARY_DEVICE_REQUIRED");
    return legacySend(db, env, auth.username, {
      from: auth.username,
      to: b.to_username,
      msgId: `v2:${auth.device_id}:${b.client_msg_id}`,
      cipher: b.cipher,
      ts: b.ts,
      recipient_public_key: b.recipient_public_key,
      sender_key_id: b.sender_key_id
    }, now, sessionGuard(db, auth, now), b.sender_key_id);
  }
  // ==================== 私钥备份（B1 密码派生 + B3 恢复码） ====================
  //
  // 安全边界：服务端只保存客户端用 PBKDF2 派生密钥加密后的 AES-GCM 密文。
  // 服务端没有密码、没有恢复码，数学上无法还原私钥 —— 端到端加密不被削弱。
  //
  // 按 subject 存（不是 username），因为 username 可以改，subject 不变。
  if (path === "/chat/v2/key-backup" && req.method === "PUT") {
    query(url, []);
    active(auth);
    const b = object(await body(req), ["pwd_blob", "code_blob", "code_hash", "kdf"]);
    text(b.pwd_blob, 8000);
    text(b.code_blob, 8000);
    text(b.kdf, 64);
    if (typeof b.code_hash !== "string" || !/^[0-9a-f]{64}$/.test(b.code_hash)) fail(400, "INVALID_REQUEST");

    await atomic(db, [
      sessionGuard(db, auth, now),
      statement(db, `INSERT INTO v2_key_backups(subject,pwd_blob,code_blob,code_hash,kdf,updated_at)
        VALUES(?,?,?,?,?,?)
        ON CONFLICT(subject) DO UPDATE SET pwd_blob=excluded.pwd_blob,code_blob=excluded.code_blob,
        code_hash=excluded.code_hash,kdf=excluded.kdf,updated_at=excluded.updated_at`,
        auth.subject, b.pwd_blob, b.code_blob, b.code_hash, b.kdf, now)
    ]);
    return { ok: true, data: { updated_at_ms: now } };
  }

  if (path === "/chat/v2/key-backup" && req.method === "GET") {
    query(url, []);
    await noBody(req);
    const row = await statement(db, `SELECT pwd_blob,code_blob,kdf,updated_at FROM v2_key_backups WHERE subject=?`, auth.subject).first();
    if (!row) fail(404, "BACKUP_NOT_FOUND");
    return { ok: true, data: {
      pwd_blob: row.pwd_blob,
      code_blob: row.code_blob,
      kdf: row.kdf,
      updated_at_ms: row.updated_at
    } };
  }

  // 凭恢复码哈希取回（忘记密码时）。返回的仍是密文，没有恢复码本身解不开。
  if (path === "/chat/v2/key-backup/lookup" && req.method === "POST") {
    query(url, []);
    active(auth);
    const b = object(await body(req), ["code_hash"]);
    if (typeof b.code_hash !== "string" || !/^[0-9a-f]{64}$/.test(b.code_hash)) fail(400, "INVALID_REQUEST");
    const row = await statement(db, `SELECT pwd_blob,code_blob,kdf,updated_at FROM v2_key_backups
      WHERE subject=? AND code_hash=?`, auth.subject, b.code_hash).first();
    if (!row) fail(404, "CODE_MISMATCH");
    return { ok: true, data: {
      pwd_blob: row.pwd_blob,
      code_blob: row.code_blob,
      kdf: row.kdf,
      updated_at_ms: row.updated_at
    } };
  }

  if (path === "/chat/v2/key-backup" && req.method === "DELETE") {
    query(url, []);
    await noBody(req);
    await statement(db, "DELETE FROM v2_key_backups WHERE subject=?", auth.subject).run();
    return { ok: true };
  }

  return null;
}
__name(androidRoute, "androidRoute");

// src/v2.js
async function v2(req, env, db, url, deps) {
  const now = deps.now();
  const path = url.pathname;
  const method = req.method;
  secret(env, "CURSOR_SECRET");
  const ok = /* @__PURE__ */ __name((data) => data === void 0 ? { ok: true } : { ok: true, data }, "ok");
  if (path === "/chat/v2/capabilities" && method === "GET") {
    query(url, []);
    await noBody(req);
    if (!await statement(db, "SELECT id FROM v2_guard WHERE id=1 AND valid=1").first()) fail(503, "NOT_CONFIGURED");
    if (!await statement(db, "SELECT version FROM v2_schema WHERE version=4").first()) fail(503, "NOT_CONFIGURED");
    if (env.HISTORY_BUCKET?.put) recoveryLimits(env);
    return ok({
      protocol: "nu-chat-v2",
      version: 2,
      algorithms: ["X25519", "P256"],
      features: [
        "cursor_sync",
        "idempotent_send",
        "device_keys",
        "receipts",
        "conversations",
        "blocks",
        "private_reads",
        "history_controls",
        "notification_preferences",
        "legacy_android_interop",
        ...env.HISTORY_BUCKET?.put ? ["encrypted_backups", "device_history_transfer"] : [],
        ...barkEnabled(env) ? ["bark"] : []
      ],
      retention_days: 365000,
      max_page_size: 200,
      max_devices_per_account: 8,
      max_plaintext_bytes: 16384,
      max_request_bytes: 524288,
      max_envelopes: 16
    });
  }
  if (path === "/chat/v2/session" && method === "POST") {
    query(url, []);
    await rate(db, `session:${req.headers.get("CF-Connecting-IP") || "unknown"}`, 10, now);
    const b = object(await body(req), ["device_id", "device_name"]);
    const deviceId = uuid(b.device_id);
    text(b.device_name, 64);
    const identity = await mainIdentityCached(req, deps.fetch);
    const owner = subject(identity);
    const d = await statement(db, "SELECT * FROM v2_devices WHERE device_id=?", deviceId).first();
    if (d && d.subject !== owner) fail(403, "FORBIDDEN");
    if (d?.revoked) fail(403, "DEVICE_REVOKED");
    const token = base64(crypto.getRandomValues(new Uint8Array(32)));
    const tokenHash = await hash(token);
    if (!d && (await statement(db, "SELECT COUNT(*) AS n FROM v2_devices WHERE subject=? AND revoked=0", owner).first()).n >= 8) fail(409, "DEVICE_LIMIT");
    await atomic(db, [
      guard(db, `NOT EXISTS(SELECT 1 FROM v2_devices WHERE device_id=? AND (subject<>? OR revoked=1))
        AND (EXISTS(SELECT 1 FROM v2_devices WHERE device_id=?) OR
        (SELECT COUNT(*) FROM v2_devices WHERE subject=? AND revoked=0)<8)`, deviceId, owner, deviceId, owner),
      statement(db, `INSERT INTO v2_accounts(subject,username) VALUES(?,?)
        ON CONFLICT(subject) DO UPDATE SET username=excluded.username`, owner, identity.username),
      statement(db, `INSERT INTO v2_devices(device_id,subject,name,created_at) VALUES(?,?,?,?)
        ON CONFLICT(device_id) DO UPDATE SET name=excluded.name`, deviceId, owner, b.device_name, now),
      statement(db, "INSERT INTO v2_sessions(token_hash,device_id,expires_at) VALUES(?,?,?)", tokenHash, deviceId, now + 36e5)
    ], "DEVICE_LIMIT_OR_IDENTITY_CONFLICT");
    return ok({ subject: owner, username: identity.username, device_id: deviceId, chat_token: token, expires_at_ms: now + 36e5 });
  }
  const auth = await authenticate(req, db, now);
  const sg = /* @__PURE__ */ __name(() => sessionGuard(db, auth, now), "sg");
  const android = await androidRoute(req, env, db, url, auth, deps);
  if (android) return android;
  const social = await socialRoute(req, env, db, url, auth, deps);
  if (social) return social;
  const notifications = await notificationRoute(req, env, db, url, auth, deps);
  if (notifications) return notifications;
  const recovery = await recoveryRoute(req, env, db, url, auth, deps);
  if (recovery) return recovery;
  if (path === "/chat/v2/session" && method === "DELETE") {
    query(url, []);
    await noBody(req);
    await statement(db, "DELETE FROM v2_sessions WHERE token_hash=?", auth.tokenHash).run();
    return ok();
  }
  if (path === "/chat/v2/devices" && method === "GET") {
    query(url, []);
    await noBody(req);
    const devices = await rows(statement(db, `SELECT device_id,name AS device_name,active,revoked,
      CAST(key_revision AS TEXT) AS key_revision,created_at AS created_at_ms FROM v2_devices WHERE subject=? ORDER BY created_at,device_id`, auth.subject));
    return ok({ devices: devices.map((d) => ({ ...d, active: !!d.active, revoked: !!d.revoked })) });
  }
  let m = path.match(/^\/chat\/v2\/devices\/([^/]+)\/keys$/);
  if (m && method === "POST") {
    query(url, []);
    const id = uuid(m[1]);
    if (id !== auth.device_id) fail(403, "FORBIDDEN");
    const b = object(await body(req), ["expected_revision", "keys"]);
    sequence(b.expected_revision);
    if (!Array.isArray(b.keys) || b.keys.length !== 2) fail(400, "INVALID_REQUEST");
    const algorithms = /* @__PURE__ */ new Set();
    const ids = /* @__PURE__ */ new Set();
    for (const k of b.keys) {
      object(k, ["key_id", "algorithm", "public_key"]);
      uuid(k.key_id);
      if (algorithms.has(k.algorithm) || ids.has(k.key_id)) fail(400, "INVALID_REQUEST");
      await publicKey(k.algorithm, k.public_key);
      algorithms.add(k.algorithm);
      ids.add(k.key_id);
    }
    const requestHash = await hash(canonical(b));
    const replay = await statement(db, "SELECT request_hash,result FROM v2_key_operations WHERE device_id=? AND expected_revision=?", id, b.expected_revision).first();
    if (replay) {
      if (replay.request_hash !== requestHash) fail(409, "REVISION_CONFLICT");
      return ok(JSON.parse(replay.result));
    }
    if (b.expected_revision !== auth.revision) fail(409, "REVISION_CONFLICT");
    const statements = [
      sg(),
      guard(db, "EXISTS(SELECT 1 FROM v2_devices WHERE device_id=? AND key_revision=? AND revoked=0)", id, b.expected_revision),
      statement(db, "UPDATE v2_devices SET key_revision=key_revision+1,active=1 WHERE device_id=?", id),
      statement(db, "UPDATE v2_accounts SET keyset_revision=keyset_revision+1 WHERE subject=?", auth.subject)
    ];
    for (const k of b.keys) statements.push(statement(db, `INSERT INTO v2_keys(key_id,device_id,algorithm,public_key,revision)
      SELECT ?,?,?,?,key_revision FROM v2_devices WHERE device_id=?`, k.key_id, id, k.algorithm, k.public_key, id));
    statements.push(
      statement(db, `INSERT INTO v2_key_operations(device_id,expected_revision,request_hash,result)
      SELECT ?,?,?,json_object('device_id',d.device_id,'key_revision',CAST(d.key_revision AS TEXT),
      'keyset_revision',CAST(a.keyset_revision AS TEXT),'active',json('true')) FROM v2_devices d
      JOIN v2_accounts a ON a.subject=d.subject WHERE d.device_id=?`, id, b.expected_revision, requestHash, id),
      keysetEvents(db, auth.subject, now)
    );
    try {
      await atomic(db, statements);
    } catch (e) {
      const r = await statement(db, "SELECT request_hash,result FROM v2_key_operations WHERE device_id=? AND expected_revision=?", id, b.expected_revision).first();
      if (r?.request_hash === requestHash) return ok(JSON.parse(r.result));
      throw e;
    }
    const result = await statement(db, "SELECT result FROM v2_key_operations WHERE device_id=? AND expected_revision=?", id, b.expected_revision).first();
    return ok(JSON.parse(result.result));
  }
  m = path.match(/^\/chat\/v2\/devices\/([^/]+)$/);
  if (m && method === "DELETE") {
    query(url, []);
    await noBody(req);
    const id = uuid(m[1]);
    const target = await statement(db, "SELECT subject,revoked FROM v2_devices WHERE device_id=?", id).first();
    if (!target) fail(404, "DEVICE_NOT_FOUND");
    if (target.subject !== auth.subject) fail(403, "FORBIDDEN");
    if (target.revoked) return ok();
    await atomic(db, [
      sg(),
      // Increment only once, even when two sessions revoke the same device.
      statement(db, `UPDATE v2_accounts SET keyset_revision=keyset_revision+1 WHERE subject=?
        AND EXISTS(SELECT 1 FROM v2_devices WHERE device_id=? AND revoked=0)`, auth.subject, id),
      statement(db, "UPDATE v2_devices SET revoked=1,active=0 WHERE device_id=?", id),
      statement(db, "DELETE FROM v2_sessions WHERE device_id=?", id),
      statement(db, "DELETE FROM v2_bark WHERE device_id=?", id),
      statement(db, "UPDATE v2_outbox SET state='cancelled',lease_token=NULL,lease_until=0 WHERE device_id=? AND state IN ('queued','leased')", id),
      statement(db, `UPDATE v2_recovery_packs SET state='deleting',revision=revision+1 WHERE kind='transfer' AND
        (source_device=? OR target_device=?) AND state IN('awaiting_approval','uploading','ready')`, id, id),
      keysetEvents(db, auth.subject, now)
    ]);
    return ok();
  }
  m = path.match(/^\/chat\/v2\/users\/([^/]+)\/keys$/);
  if (m && method === "GET") {
    query(url, []);
    await noBody(req);
    let name;
    try {
      name = decodeURIComponent(m[1]);
    } catch {
      fail(400, "INVALID_REQUEST");
    }
    username(name);
    const a = await statement(db, "SELECT subject FROM v2_accounts WHERE username=?", name).first();
    if (!a) fail(404, "USER_NOT_FOUND");
    return ok(await bundle(db, a.subject));
  }
  if (path === "/chat/v2/messages" && method === "POST") {
    query(url, []);
    active(auth);
    const b = object(await body(req), [
      "client_msg_id",
      "to_subject",
      "client_sent_at_ms",
      "sender_keyset_revision",
      "recipient_keyset_revision",
      "envelopes"
    ]);
    uuid(b.client_msg_id);
    text(b.to_subject);
    sequence(b.sender_keyset_revision);
    sequence(b.recipient_keyset_revision);
    if (!Number.isSafeInteger(b.client_sent_at_ms) || b.client_sent_at_ms < 0 || b.to_subject === auth.subject) fail(400, "INVALID_REQUEST");
    const requestHash = await hash(canonical(b));
    const prior = await accepted(db, auth, b.client_msg_id);
    if (prior) {
      if (prior.request_hash !== requestHash) fail(409, "IDEMPOTENCY_CONFLICT");
      return ok(acceptance(prior, true));
    }
    await rate(db, `send:${auth.subject}`, 30, now);
    try {
      await atomic(db, [deliveryGuard(db, auth.subject, b.to_subject)], "DELIVERY_NOT_ALLOWED");
    } catch {
      fail(409, "DELIVERY_NOT_ALLOWED");
    }
    const sender = await bundle(db, auth.subject);
    const recipient = await bundle(db, b.to_subject);
    if (!recipient.devices.length) fail(409, "NO_ACTIVE_DEVICE");
    if (sender.keyset_revision !== b.sender_keyset_revision || recipient.keyset_revision !== b.recipient_keyset_revision) fail(409, "KEYSET_CHANGED");
    const targets = /* @__PURE__ */ new Map();
    for (const account of [sender, recipient]) for (const device of account.devices) targets.set(device.device_id, { account, device });
    if (!Array.isArray(b.envelopes) || b.envelopes.length !== targets.size || targets.size > 16) fail(400, "INVALID_REQUEST");
    const seen = /* @__PURE__ */ new Set();
    const local = sender.devices.find((d) => d.device_id === auth.device_id);
    for (const e of b.envelopes) {
      object(e, ["target_subject", "target_device_id", "algorithm", "sender_key_id", "recipient_key_id", "nonce", "ciphertext"]);
      uuid(e.target_device_id);
      uuid(e.sender_key_id);
      uuid(e.recipient_key_id);
      const target = targets.get(e.target_device_id);
      if (!target || seen.has(e.target_device_id) || target.account.subject !== e.target_subject) fail(400, "INVALID_REQUEST");
      seen.add(e.target_device_id);
      const recipientKey = target.device.keys.find((k) => k.key_id === e.recipient_key_id && k.algorithm === e.algorithm);
      if (!recipientKey || !local?.keys.some((k) => k.key_id === e.sender_key_id && k.algorithm === e.algorithm)) fail(409, "KEYSET_CHANGED");
      if (unbase64(e.nonce, 12).length !== 12 || unbase64(e.ciphertext, 16400).length < 16) fail(400, "INVALID_REQUEST");
    }
    const [a, z] = [auth.subject, b.to_subject].sort();
    const messageId = crypto.randomUUID();
    const sameRequest = `EXISTS(SELECT 1 FROM v2_messages WHERE sender=? AND sender_device=? AND client_msg_id=? AND request_hash=?)`;
    const sameArgs = [auth.subject, auth.device_id, b.client_msg_id, requestHash];
    const stmts = [
      sg(),
      deliveryGuard(db, auth.subject, b.to_subject),
      guard(
        db,
        `${sameRequest} OR (
      EXISTS(SELECT 1 FROM v2_accounts WHERE subject=? AND keyset_revision=?) AND
      EXISTS(SELECT 1 FROM v2_accounts WHERE subject=? AND keyset_revision=?))`,
        ...sameArgs,
        auth.subject,
        b.sender_keyset_revision,
        b.to_subject,
        b.recipient_keyset_revision
      ),
      guard(db, `NOT EXISTS(SELECT 1 FROM v2_messages WHERE sender=? AND sender_device=? AND client_msg_id=? AND request_hash<>?)`, ...sameArgs),
      statement(db, `INSERT INTO v2_conversations(conversation_id,a,b) VALUES(?,?,?) ON CONFLICT(a,b) DO NOTHING`, crypto.randomUUID(), a, z),
      statement(
        db,
        `INSERT INTO v2_messages(message_id,client_msg_id,sender,sender_device,recipient,conversation_id,request_hash,accepted_at,expires_at)
      SELECT ?,?,?,?,?,conversation_id,?,?,? FROM v2_conversations WHERE a=? AND b=?
      ON CONFLICT(sender,sender_device,client_msg_id) DO NOTHING`,
        messageId,
        b.client_msg_id,
        auth.subject,
        auth.device_id,
        b.to_subject,
        requestHash,
        now,
        now + KEEP_FOREVER,
        a,
        z
      )
    ];
    for (const owner of [auth.subject, b.to_subject]) stmts.push(statement(
      db,
      `INSERT INTO v2_copies(owner,message_id,expires_at)
      SELECT ?,m.message_id,MIN(m.expires_at,m.accepted_at+COALESCE((SELECT retention_days FROM v2_preferences WHERE subject=?),30)*?)
      FROM v2_messages m WHERE m.sender=? AND m.sender_device=? AND m.client_msg_id=? ON CONFLICT(owner,message_id) DO NOTHING`,
      owner,
      owner,
      DAY,
      auth.subject,
      auth.device_id,
      b.client_msg_id
    ));
    for (const e of b.envelopes) {
      stmts.push(statement(db, `INSERT INTO v2_events(device_id,dedup,type,message_id,payload,expires_at)
        SELECT ?,'msg:'||m.message_id,'message',m.message_id,
        json_object('type','message','message_id',m.message_id,'client_msg_id',m.client_msg_id,
        'conversation_id',m.conversation_id,'conversation_seq',CAST(m.seq AS TEXT),
        'sender_subject',m.sender,'sender_device_id',m.sender_device,'recipient_subject',m.recipient,
        'accepted_at_ms',m.accepted_at,'expires_at_ms',cp.expires_at,'envelope',json(?)),cp.expires_at
        FROM v2_messages m JOIN v2_copies cp ON cp.message_id=m.message_id AND cp.owner=? WHERE m.sender=? AND m.sender_device=? AND m.client_msg_id=?
        ON CONFLICT(device_id,dedup) DO NOTHING`, e.target_device_id, JSON.stringify(e), e.target_subject, auth.subject, auth.device_id, b.client_msg_id));
      if (barkEnabled(env) && e.target_subject === b.to_subject) stmts.push(statement(
        db,
        `INSERT INTO v2_outbox
        (notification_id,device_id,message_id,binding_version,next_at,created_at)
        SELECT ?,?,m.message_id,k.version,?,? FROM v2_messages m JOIN v2_bark k ON k.device_id=? AND k.enabled=1
        WHERE m.sender=? AND m.sender_device=? AND m.client_msg_id=? ON CONFLICT(message_id,device_id) DO NOTHING`,
        crypto.randomUUID(),
        e.target_device_id,
        now,
        now,
        e.target_device_id,
        auth.subject,
        auth.device_id,
        b.client_msg_id
      ));
    }
    try {
      await atomic(db, stmts, "KEYSET_CHANGED");
    } catch (e) {
      const replay = await accepted(db, auth, b.client_msg_id);
      if (replay?.request_hash === requestHash) return ok(acceptance(replay, true));
      if (replay) fail(409, "IDEMPOTENCY_CONFLICT");
      throw e;
    }
    const row = await accepted(db, auth, b.client_msg_id);
    return ok(acceptance(row, row.message_id !== messageId));
  }
  if (path === "/chat/v2/sync" && method === "GET") {
    query(url, ["cursor", "limit"]);
    await noBody(req);
    active(auth);
    const n = url.searchParams.get("limit") ?? "100";
    if (!/^[1-9][0-9]{0,2}$/.test(n) || Number(n) > 200) fail(400, "INVALID_REQUEST");
    const limit = Number(n);
    const floor = await currentFloor(db, auth.device_id, now);
    const c = url.searchParams.has("cursor") ? await parseCursor(env, auth, url.searchParams.get("cursor")) : { p: "0", f: "0" };
    if (BigInt(c.p) < BigInt(floor) && BigInt(c.f) < BigInt(floor)) fail(410, "CURSOR_EXPIRED");
    const list = await rows(statement(db, `WITH v AS(SELECT ? AS owner,? AS now)
      SELECT CAST(e.seq AS TEXT) AS seq,CASE WHEN e.type='message' AND NOT(${visible()})
      THEN json_set(json_remove(e.payload,'$.envelope'),'$.unavailable',CASE WHEN m.recalled_at IS NOT NULL THEN 'recalled' ELSE 'hidden' END)
      WHEN e.type='message' THEN ${messagePayload()} ELSE e.payload END AS payload FROM v2_events e CROSS JOIN v LEFT JOIN v2_messages m ON m.message_id=e.message_id
      WHERE e.device_id=? AND e.seq>? AND e.expires_at>? ORDER BY e.seq LIMIT ?`, auth.subject, now, auth.device_id, c.p, now, limit + 1));
    const hasMore = list.length > limit;
    const page = list.slice(0, limit);
    const next = page.at(-1)?.seq ?? c.p;
    await atomic(db, [sg(), statement(db, "UPDATE v2_devices SET max_issued_seq=MAX(max_issued_seq,CAST(? AS INTEGER)) WHERE device_id=?", next, auth.device_id)]);
    return ok({
      events: page.map((e) => ({ event_seq: e.seq, ...JSON.parse(e.payload) })),
      next_cursor: await signCursor(env, auth, next, floor),
      has_more: hasMore,
      server_time_ms: now
    });
  }
  if (path === "/chat/v2/sync/reset" && method === "POST") {
    query(url, []);
    active(auth);
    const b = object(await body(req), ["reason"]);
    if (b.reason !== "cursor_expired") fail(400, "INVALID_REQUEST");
    const floor = await currentFloor(db, auth.device_id, now);
    const first = await statement(db, `SELECT MIN(expires_at)-? AS available FROM v2_events WHERE device_id=? AND expires_at>?`, KEEP_FOREVER, auth.device_id, now).first();
    return ok({ cursor: await signCursor(env, auth, "0", floor), available_from_ms: first.available ?? now, history_complete: false });
  }
  if (path === "/chat/v2/sync/ack" && method === "POST") {
    query(url, []);
    active(auth);
    const b = object(await body(req), ["cursor"]);
    const c = await parseCursor(env, auth, text(b.cursor, 2048));
    const floor = await currentFloor(db, auth.device_id, now);
    if (BigInt(c.p) < BigInt(floor) && BigInt(c.f) < BigInt(floor)) fail(410, "CURSOR_EXPIRED");
    if (BigInt(c.p) > BigInt(auth.issued)) fail(400, "INVALID_CURSOR");
    await atomic(db, [
      sg(),
      statement(db, `INSERT INTO v2_deliveries(message_id,device_id,received_at)
        WITH v AS(SELECT ? AS owner,? AS now)
        SELECT m.message_id,?,? FROM v2_events e CROSS JOIN v JOIN v2_messages m ON m.message_id=e.message_id
        WHERE e.device_id=? AND e.type='message' AND e.seq<=? AND e.expires_at>?
        AND m.recipient=? AND ${visible()} ON CONFLICT(message_id,device_id) DO NOTHING`, auth.subject, now, auth.device_id, now, auth.device_id, c.p, now, auth.subject),
      statement(db, "UPDATE v2_devices SET ack_seq=MAX(ack_seq,CAST(? AS INTEGER)) WHERE device_id=?", c.p, auth.device_id),
      statement(db, `INSERT INTO v2_events(device_id,dedup,type,message_id,payload,expires_at)
        SELECT d.device_id,'delivered:'||r.message_id||':'||r.device_id,'delivered',r.message_id,
        json_object('type','delivered','conversation_id',m.conversation_id,'message_id',m.message_id,
        'receiver_subject',m.recipient,'receiver_device_id',r.device_id,'received_at_ms',r.received_at),?
        FROM v2_deliveries r JOIN v2_messages m ON m.message_id=r.message_id
        JOIN v2_devices d ON d.subject IN (m.sender,m.recipient) AND d.active=1 AND d.revoked=0
        WHERE r.device_id=? AND m.expires_at>? ON CONFLICT(device_id,dedup) DO NOTHING`, now + KEEP_FOREVER, auth.device_id, now)
    ]);
    return ok();
  }
  m = path.match(/^\/chat\/v2\/conversations\/([^/]+)\/read$/);
  if (m && method === "POST") {
    query(url, []);
    active(auth);
    const id = uuid(m[1]);
    await conversation(db, id, auth.subject);
    const b = object(await body(req), ["up_to_conversation_seq"]);
    const upto = sequence(b.up_to_conversation_seq);
    const received = await statement(db, `WITH v AS(SELECT ? AS owner,? AS now)
      SELECT CAST(e.seq AS TEXT) AS seq FROM v2_events e CROSS JOIN v JOIN v2_messages m ON m.message_id=e.message_id
      WHERE e.device_id=? AND e.type='message' AND m.conversation_id=? AND m.seq=?
      AND m.recipient=? AND e.seq<=? AND e.expires_at>? AND ${visible()}`, auth.subject, now, auth.device_id, id, upto, auth.subject, auth.ack, now).first();
    if (!received) fail(400, "INVALID_READ_POSITION");
    await atomic(db, [
      sg(),
      statement(db, `INSERT INTO v2_reads(conversation_id,reader,upto_seq,read_at,disclosed_seq)
        VALUES(?,?,?,?,CASE WHEN COALESCE((SELECT read_receipts FROM v2_preferences WHERE subject=?),1)=1 THEN ? ELSE 0 END)
        ON CONFLICT(conversation_id,reader) DO UPDATE SET upto_seq=MAX(v2_reads.upto_seq,excluded.upto_seq),
        disclosed_seq=MAX(v2_reads.disclosed_seq,excluded.disclosed_seq),
        read_at=CASE WHEN excluded.upto_seq>v2_reads.upto_seq THEN excluded.read_at ELSE v2_reads.read_at END`, id, auth.subject, upto, now, auth.subject, upto),
      statement(db, `INSERT INTO v2_events(device_id,dedup,type,payload,expires_at)
        SELECT d.device_id,'read:'||r.conversation_id||':'||r.reader||':'||r.upto_seq,'read',
        json_object('type','read','conversation_id',r.conversation_id,'reader_subject',r.reader,
        'up_to_conversation_seq',CAST(r.upto_seq AS TEXT),'read_at_ms',r.read_at),?
        FROM v2_reads r JOIN v2_conversations c ON c.conversation_id=r.conversation_id
        JOIN v2_devices d ON d.subject IN(c.a,c.b) AND d.active=1 AND d.revoked=0
        WHERE r.conversation_id=? AND r.reader=? AND (d.subject=r.reader OR COALESCE((SELECT read_receipts FROM v2_preferences WHERE subject=r.reader),1)=1)
        ON CONFLICT(device_id,dedup) DO NOTHING`, now + KEEP_FOREVER, id, auth.subject)
    ]);
    return ok(await statement(db, `SELECT reader AS reader_subject,CAST(upto_seq AS TEXT) AS up_to_conversation_seq,
      read_at AS read_at_ms FROM v2_reads WHERE conversation_id=? AND reader=?`, id, auth.subject).first());
  }
  if (/^\/chat\/v2\/devices\/[^/]+\/bark(?:\/test|\/status)?$/.test(path)) {
    return barkRoute(req, env, db, url, auth, deps);
  }
  fail(404, "NOT_FOUND");
}
__name(v2, "v2");

// src/legacy.js
async function legacy(req, env, db, url, deps) {
  const path = url.pathname;
  const now = deps.now();
  if (path === "/chat/ping") {
    query(url, []);
    await noBody(req);
    return {
      ok: true,
      ts: now,
      mode: "d1",
      protocol: "legacy-d1",
      retention: "operator-managed",
      limits: { page: 500 },
      v2_path: "/chat/v2/capabilities"
    };
  }
  if (path.startsWith("/chat/key/")) {
    query(url, []);
    await noBody(req);
    let user;
    try {
      user = decodeURIComponent(path.slice(10));
    } catch {
      fail(400, "INVALID_REQUEST");
    }
    username(user);
    let algorithm;
    if (req.headers.has("Authorization")) {
      const identity2 = await mainIdentityCached(req, deps.fetch);
      const own = await statement(db, "SELECT public_key FROM users WHERE username=?", identity2.username).first();
      if (own) algorithm = await legacyAlgorithm(own.public_key);
    }
    const peer = await legacyPeer(db, user, algorithm);
    return { ok: true, data: { userId: user, publicKey: peer.public_key } };
  }
  const identity = await mainIdentityCached(req, deps.fetch);
  const me = identity.username;
  const owner = subject(identity);
  await atomic(db, [statement(db, `INSERT INTO v2_accounts(subject,username) VALUES(?,?)
    ON CONFLICT(subject) DO UPDATE SET username=excluded.username`, owner, me)], "IDENTITY_CONFLICT");
  if (path === "/chat/key") {
    query(url, []);
    const b = object(await body(req), ["publicKey"], ["userId", "username", "expectedPublicKey"]);
    if (b.userId !== void 0 && b.userId !== me || b.username !== void 0 && b.username !== me) fail(403, "FORBIDDEN");
    let valid = false;
    for (const algorithm of ["X25519", "P256"]) {
      try {
        await publicKey(algorithm, b.publicKey);
        valid = true;
        break;
      } catch {
      }
    }
    if (!valid) fail(400, "INVALID_REQUEST");
    const binding = await statement(db, "SELECT device_id FROM v2_legacy_bindings WHERE subject=? AND enabled=1", owner).first();
    if (binding) {
      const bound = await legacyPeer(db, me, await legacyAlgorithm(b.publicKey));
      if (bound.public_key !== b.publicKey) fail(409, "LEGACY_ACCOUNT_ALREADY_IN_USE");
      return { ok: true, userId: me };
    }
    const old = await statement(db, "SELECT public_key FROM users WHERE username=?", me).first();
    if (old && old.public_key !== b.publicKey) {
      if (old.public_key !== b.expectedPublicKey) fail(409, "KEY_CHANGE_REQUIRES_CONFIRMATION");
      const result = await statement(db, "UPDATE users SET public_key=?,updated_at=? WHERE username=? AND public_key=?", b.publicKey, now, me, b.expectedPublicKey).run();
      if (!result.meta.changes) fail(409, "KEYSET_CHANGED");
    } else if (!old) {
      await statement(db, `INSERT INTO users(username,public_key,updated_at) VALUES(?,?,?) ON CONFLICT(username) DO NOTHING`, me, b.publicKey, now).run();
      const row = await statement(db, "SELECT public_key FROM users WHERE username=?", me).first();
      if (row.public_key !== b.publicKey) fail(409, "KEYSET_CHANGED");
    }
    await atomic(db, [
      guard(db, "EXISTS(SELECT 1 FROM users WHERE username=? AND public_key=?)", me, b.publicKey),
      statement(db, "INSERT INTO v2_legacy_users(username,subject) VALUES(?,?) ON CONFLICT(username) DO UPDATE SET subject=excluded.subject", me, owner)
    ]);
    return { ok: true, userId: me };
  }
  if (path === "/chat/send") {
    query(url, []);
    const b = object(await body(req), ["to", "msgId", "cipher", "ts"], ["from"]);
    username(b.to);
    text(b.msgId);
    text(b.cipher, 24e3);
    if (b.from !== void 0 && b.from !== me) fail(403, "FORBIDDEN");
    if (!Number.isSafeInteger(b.ts) || b.ts < 0) fail(400, "INVALID_REQUEST");
    const parts = b.cipher.split(":");
    if (parts.length !== 2 || unbase64(parts[0], 12).length !== 12 || unbase64(parts[1], 16400).length < 16) fail(400, "INVALID_REQUEST");
    if (await statement(db, "SELECT 1 FROM v2_legacy_bindings WHERE subject=? AND enabled=1", owner).first()) fail(409, "LEGACY_PRIMARY_DEVICE_REQUIRED");
    return legacySend(db, env, me, { to: b.to, msgId: b.msgId, cipher: b.cipher, ts: b.ts }, now);
  }
  if (path === "/chat/pull") {
    // PULL_POST_OK: 部分网络会 RST 明文 GET，这里同时接受 POST + JSON body
    let pullBody = null;
    if (req.method === "POST") {
      try { pullBody = await body(req); } catch (e) { pullBody = null; }
    } else {
      query(url, ["cursor", "since", "limit"]);
      await noBody(req);
    }
    if (url.searchParams.has("since") && url.searchParams.get("since") !== "0") fail(409, "LEGACY_CURSOR_UNSUPPORTED");
    const cursor = (pullBody && pullBody.cursor != null ? String(pullBody.cursor) : url.searchParams.get("cursor")) ?? "0";
    const limit = (pullBody && pullBody.limit != null ? String(pullBody.limit) : url.searchParams.get("limit")) ?? "200";
    if (!/^(0|[1-9][0-9]*)$/.test(cursor) || !Number.isSafeInteger(Number(cursor)) || !/^[1-9][0-9]{0,2}$/.test(limit) || Number(limit) > 500) fail(400, "INVALID_REQUEST");
    const list = await rows(statement(db, `WITH v AS(SELECT ? AS owner,? AS now)
      SELECT old.seq,old.msg_id AS msgId,old.from_user AS 'from',old.to_user AS 'to',old.cipher,old.ts,
      CASE WHEN m.message_id IS NULL THEN 0 ELSE (${visible()}) END AS allowed
      FROM messages old CROSS JOIN v LEFT JOIN v2_legacy_link l ON l.legacy_seq=old.seq
      LEFT JOIN v2_messages m ON m.message_id=l.message_id
      WHERE(old.from_user=? OR old.to_user=?) AND old.seq>? ORDER BY old.seq LIMIT ?`, owner, now, me, me, Number(cursor), Number(limit) + 1));
    const page = list.slice(0, Number(limit));
    return { ok: true, data: page.filter((r) => r.allowed).map(({ allowed, ...r }) => r), next_cursor: page.at(-1)?.seq ?? Number(cursor), has_more: list.length > Number(limit), server_time_ms: now };
  }
  if (path === "/chat/read" && req.method === "GET") {
    query(url, ["peerId"]);
    await noBody(req);
    const peer = username(url.searchParams.get("peerId"));
    const peerOwner = await statement(db, "SELECT subject FROM v2_accounts WHERE username=?", peer).first();
    const mine = await statement(db, `SELECT MAX(old.seq) AS upto_seq FROM messages old
      JOIN v2_legacy_link l ON l.legacy_seq=old.seq JOIN v2_messages m ON m.message_id=l.message_id
      JOIN v2_reads r ON r.conversation_id=m.conversation_id AND r.reader=?
      WHERE m.sender=? AND m.recipient=? AND m.seq<=r.upto_seq`, owner, peerOwner?.subject ?? "", owner).first();
    const theirs = await statement(db, `SELECT MAX(old.seq) AS upto_seq FROM messages old
      JOIN v2_legacy_link l ON l.legacy_seq=old.seq JOIN v2_messages m ON m.message_id=l.message_id
      JOIN v2_reads r ON r.conversation_id=m.conversation_id AND r.reader=?
      WHERE m.sender=? AND m.recipient=? AND m.seq<=r.disclosed_seq`, peerOwner?.subject ?? "", owner, peerOwner?.subject ?? "").first();
    return { ok: true, data: { peerId: peer, own_upto: mine?.upto_seq ?? 0, peer_upto: theirs?.upto_seq ?? 0 } };
  }
  if (path === "/chat/read") {
    query(url, []);
    const b = object(await body(req), ["peerId", "upto"]);
    const peer = username(b.peerId);
    if (!Number.isSafeInteger(b.upto) || b.upto <= 0) fail(400, "INVALID_READ_POSITION");
    if (!await statement(db, "SELECT 1 FROM messages WHERE seq=? AND from_user=? AND to_user=?", b.upto, peer, me).first()) fail(400, "INVALID_READ_POSITION");
    const mapped = await statement(db, `WITH v AS(SELECT ? AS owner,? AS now)
      SELECT m.seq,m.conversation_id FROM v2_legacy_link l CROSS JOIN v JOIN v2_messages m ON m.message_id=l.message_id
      WHERE l.legacy_seq=? AND m.recipient=? AND ${visible()}`, owner, now, b.upto, owner).first();
    if (!mapped) fail(400, "INVALID_READ_POSITION");
    const statements = [statement(db, `INSERT INTO reads(reader,peer,upto_seq,updated_at) VALUES(?,?,?,?)
      ON CONFLICT(reader,peer) DO UPDATE SET upto_seq=MAX(reads.upto_seq,excluded.upto_seq),
      updated_at=CASE WHEN excluded.upto_seq>reads.upto_seq THEN excluded.updated_at ELSE reads.updated_at END`, me, peer, b.upto, now)];
    if (mapped) statements.push(
      statement(db, `INSERT INTO v2_reads(conversation_id,reader,upto_seq,read_at,disclosed_seq)
      VALUES(?,?,?,?,CASE WHEN COALESCE((SELECT read_receipts FROM v2_preferences WHERE subject=?),1)=1 THEN ? ELSE 0 END)
      ON CONFLICT(conversation_id,reader) DO UPDATE SET upto_seq=MAX(upto_seq,excluded.upto_seq),disclosed_seq=MAX(disclosed_seq,excluded.disclosed_seq),
      read_at=CASE WHEN excluded.upto_seq>upto_seq THEN excluded.read_at ELSE read_at END`, mapped.conversation_id, owner, mapped.seq, now, owner, mapped.seq),
      statement(db, `INSERT INTO v2_events(device_id,dedup,type,payload,expires_at)
        SELECT d.device_id,'read:'||r.conversation_id||':'||r.reader||':'||r.upto_seq,'read',
        json_object('type','read','conversation_id',r.conversation_id,'reader_subject',r.reader,'up_to_conversation_seq',CAST(r.upto_seq AS TEXT),'read_at_ms',r.read_at),?
        FROM v2_reads r JOIN v2_conversations c ON c.conversation_id=r.conversation_id JOIN v2_devices d ON d.subject IN(c.a,c.b) AND d.active=1 AND d.revoked=0
        WHERE r.conversation_id=? AND r.reader=? AND(d.subject=r.reader OR COALESCE((SELECT read_receipts FROM v2_preferences WHERE subject=r.reader),1)=1)
        ON CONFLICT(device_id,dedup) DO NOTHING`, now + KEEP_FOREVER, mapped.conversation_id, owner)
    );
    await atomic(db, statements);
    return { ok: true };
  }
  // ==================== 设备登记（v1 入口） ====================
  //
  // 新装机首次使用私聊时调用。客户端带上主站 token，报告自己的
  // platform（android/ios）和 device_code（由 user_id 派生，重装不变）。
  // 服务端记录后，返回这个平台对应的节点入口。
  //
  // chat_base 目前就是本 Worker 自己的地址。之所以由服务端下发而不是
  // 客户端写死，是为了将来可以把 android/ios 分别迁移到不同 Worker
  // 而不需要发版 —— 服务端改一行配置即可。
  if (path === "/chat/device/register" && req.method === "POST") {
    query(url, []);
    const b = object(await body(req), ["device_code", "platform"], ["app_version"]);
    if (typeof b.device_code !== "string" || !/^[0-9a-f]{64}$/.test(b.device_code)) fail(400, "INVALID_REQUEST");
    if (b.platform !== "android" && b.platform !== "ios") fail(400, "INVALID_REQUEST");
    text(b.app_version, 32);

    await atomic(db, [
      statement(db, `INSERT INTO device_registry(subject,device_code,platform,app_version,first_seen,last_seen)
        VALUES(?,?,?,?,?,?)
        ON CONFLICT(subject,device_code) DO UPDATE SET
          platform=excluded.platform, app_version=excluded.app_version, last_seen=excluded.last_seen`,
        owner, b.device_code, b.platform, b.app_version || "", now, now)
    ]);

    // 平台 → 节点入口映射。改这里即可把某平台迁去别的域名。
    const CHAT_NODES = {
      android: { chat_base: "https://nu-pe-chat.cc.cd", entry_prefix: "/Android", protocol: "v1-legacy" },
      ios:     { chat_base: "https://nu-pe-chat.cc.cd", entry_prefix: "/iOS",     protocol: "v2" }
    };
    const node = CHAT_NODES[b.platform];
    return { ok: true, data: {
      platform: b.platform,
      chat_base: node.chat_base,
      entry_prefix: node.entry_prefix,
      protocol: node.protocol,
      registered_at_ms: now
    } };
  }

  // ==================== 私钥备份（v1 入口） ====================
  //
  // Android 客户端走 legacy（v1）鉴权：Bearer 主站 token → mainIdentity → username。
  // 数据仍写进 v2_key_backups，用 subject 做键，所以与 v2 侧完全互通 ——
  // 一个人用 Android 备份、用 iOS 恢复也能读到同一行。
  if (path === "/chat/key-backup" && req.method === "PUT") {
    query(url, []);
    const b = object(await body(req), ["pwd_blob", "code_blob", "code_hash", "kdf"]);
    text(b.pwd_blob, 8000);
    text(b.code_blob, 8000);
    text(b.kdf, 64);
    if (typeof b.code_hash !== "string" || !/^[0-9a-f]{64}$/.test(b.code_hash)) fail(400, "INVALID_REQUEST");

    await atomic(db, [
      statement(db, `INSERT INTO v2_key_backups(subject,pwd_blob,code_blob,code_hash,kdf,updated_at)
        VALUES(?,?,?,?,?,?)
        ON CONFLICT(subject) DO UPDATE SET pwd_blob=excluded.pwd_blob,code_blob=excluded.code_blob,
        code_hash=excluded.code_hash,kdf=excluded.kdf,updated_at=excluded.updated_at`,
        owner, b.pwd_blob, b.code_blob, b.code_hash, b.kdf, now)
    ]);
    return { ok: true, data: { updated_at_ms: now } };
  }

  if (path === "/chat/key-backup" && req.method === "GET") {
    query(url, []);
    await noBody(req);
    const row = await statement(db, "SELECT pwd_blob,code_blob,kdf,updated_at FROM v2_key_backups WHERE subject=?", owner).first();
    if (!row) fail(404, "BACKUP_NOT_FOUND");
    return { ok: true, data: {
      pwd_blob: row.pwd_blob,
      code_blob: row.code_blob,
      kdf: row.kdf,
      updated_at_ms: row.updated_at
    } };
  }

  if (path === "/chat/key-backup/lookup" && req.method === "POST") {
    query(url, []);
    const b = object(await body(req), ["code_hash"]);
    if (typeof b.code_hash !== "string" || !/^[0-9a-f]{64}$/.test(b.code_hash)) fail(400, "INVALID_REQUEST");
    const row = await statement(db, `SELECT pwd_blob,code_blob,kdf,updated_at FROM v2_key_backups
      WHERE subject=? AND code_hash=?`, owner, b.code_hash).first();
    if (!row) fail(404, "CODE_MISMATCH");
    return { ok: true, data: {
      pwd_blob: row.pwd_blob,
      code_blob: row.code_blob,
      kdf: row.kdf,
      updated_at_ms: row.updated_at
    } };
  }

  if (path === "/chat/key-backup" && req.method === "DELETE") {
    query(url, []);
    await noBody(req);
    await statement(db, "DELETE FROM v2_key_backups WHERE subject=?", owner).run();
    return { ok: true };
  }

  fail(404, "NOT_FOUND");
}
__name(legacy, "legacy");

// src/index.js
var routes = [
  [/^\/chat\/ping$/, ["GET"]],
  [/^\/chat\/key$/, ["POST"]],
  [/^\/chat\/key\/[^/]+$/, ["GET"]],
  [/^\/chat\/send$/, ["POST"]],
  [/^\/chat\/pull$/, ["GET"]],
  [/^\/chat\/read$/, ["GET", "POST"]],
  // 私钥备份（v1 入口，Android 使用）。GET 会被客户端伪造成 POST+__alias。
  [/^\/chat\/device\/register$/, ["POST"]],
  [/^\/chat\/key-backup$/, ["GET", "POST", "PUT", "DELETE"]],
  [/^\/chat\/key-backup\/lookup$/, ["POST"]],
  [/^\/chat\/v2\/capabilities$/, ["GET"]],
  [/^\/chat\/v2\/session$/, ["POST", "DELETE"]],
  [/^\/chat\/v2\/devices$/, ["GET"]],
  [/^\/chat\/v2\/devices\/[^/]+$/, ["DELETE"]],
  [/^\/chat\/v2\/devices\/[^/]+\/keys$/, ["POST"]],
  [/^\/chat\/v2\/users\/[^/]+\/keys$/, ["GET"]],
  [/^\/chat\/v2\/messages$/, ["POST"]],
  [/^\/chat\/v2\/sync$/, ["GET"]],
  [/^\/chat\/v2\/sync\/(ack|reset)$/, ["POST"]],
  [/^\/chat\/v2\/conversations\/[^/]+\/read$/, ["POST"]],
  [/^\/chat\/v2\/devices\/[^/]+\/bark$/, ["GET", "PUT", "DELETE"]],
  [/^\/chat\/v2\/devices\/[^/]+\/bark\/test$/, ["POST"]],
  [/^\/chat\/v2\/devices\/[^/]+\/bark\/status$/, ["GET"]],
  [/^\/chat\/v2\/devices\/[^/]+\/legacy$/, ["GET", "POST"]],
  [/^\/chat\/v2\/users\/[^/]+\/protocols$/, ["GET"]],
  [/^\/chat\/v2\/legacy\/messages$/, ["POST"]],
  // 私钥备份：GET 会被客户端伪造成 POST+__alias，故 GET 路径同时允许两种方法
  [/^\/chat\/v2\/key-backup$/, ["GET", "POST", "PUT", "DELETE"]],
  [/^\/chat\/v2\/key-backup\/lookup$/, ["POST"]],
  [/^\/chat\/v2\/me\/preferences$/, ["GET", "PATCH"]],
  [/^\/chat\/v2\/blocks$/, ["GET"]],
  [/^\/chat\/v2\/blocks\/[^/]+$/, ["PUT", "DELETE"]],
  [/^\/chat\/v2\/conversations$/, ["GET"]],
  [/^\/chat\/v2\/conversations\/[^/]+\/state$/, ["GET", "PATCH"]],
  [/^\/chat\/v2\/conversations\/[^/]+\/clear$/, ["POST"]],
  [/^\/chat\/v2\/conversations\/[^/]+\/messages$/, ["GET"]],
  [/^\/chat\/v2\/messages\/[^/]+$/, ["DELETE"]],
  [/^\/chat\/v2\/messages\/[^/]+\/recall$/, ["POST"]],
  [/^\/chat\/v2\/(me|devices\/[^/]+)(\/conversations\/[^/]+)?\/notifications$/, ["GET", "PATCH"]],
  [/^\/chat\/v2\/history\/(quota|filters)$/, ["GET"]],
  [/^\/chat\/v2\/history\/(backups|transfers)$/, ["GET", "POST"]],
  [/^\/chat\/v2\/history\/(backups|transfers)\/[^/]+$/, ["GET", "DELETE"]],
  [/^\/chat\/v2\/history\/transfers\/[^/]+\/approve$/, ["POST"]],
  [/^\/chat\/v2\/history\/(backups|transfers)\/[^/]+\/complete$/, ["POST"]],
  [/^\/chat\/v2\/history\/transfers\/[^/]+\/ack$/, ["POST"]],
  [/^\/chat\/v2\/history\/(backups|transfers)\/[^/]+\/chunks\/[0-9]+$/, ["GET", "PUT"]]
];
function createWorker(overrides = {}) {
  const deps = { fetch: /* @__PURE__ */ __name((...args) => fetch(...args), "fetch"), now: /* @__PURE__ */ __name(() => Date.now(), "now"), random: /* @__PURE__ */ __name(() => Math.random(), "random"), ...overrides };
  return {
    async fetch(req, env, ctx) {
      const requestId = crypto.randomUUID();
      const url = new URL(req.url);
      // PLATFORM_PREFIX: /iOS and /Android are aliases of the same routes
    if (url.pathname.indexOf("/Android/") === 0) url.pathname = "/" + url.pathname.slice(9);
    else if (url.pathname.indexOf("/iOS/") === 0) url.pathname = "/" + url.pathname.slice(5);
    // POST_ALIAS: 部分网络会 RST 明文 GET，允许 POST + JSON body 模拟 GET
      if (req.method === "POST" && url.searchParams.get("__alias") === "1") {
        let aliasObj = {};
        try { aliasObj = JSON.parse((await req.text()) || "{}"); } catch (e) { aliasObj = {}; }
        for (const ak of Object.keys(aliasObj)) url.searchParams.set(ak, String(aliasObj[ak]));
        url.searchParams.delete("__alias");
        req = new Request(url.toString(), { method: "GET", headers: req.headers });
      }
            const modern = url.pathname.startsWith("/chat/v2/");
      try {
        
            /* ALLOW_HTTP: 客户端网络对 Cloudflare 的 TLS 全阻断，正文已端到端加密，明文可接受 */
        if (url.username || url.password || url.searchParams.has("token")) fail(400, "INVALID_REQUEST");
        const route = routes.find(([pattern]) => pattern.test(url.pathname));
        if (!route) fail(404, "NOT_FOUND");
        if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": [...route[1], "OPTIONS"].join(","),
          "Access-Control-Allow-Headers": "Authorization,Content-Type,X-Chunk-SHA256",
          "Cache-Control": "no-store"
        } });
        if (!route[1].includes(req.method)) throw new ApiError(405, "METHOD_NOT_ALLOWED", void 0, { Allow: route[1].join(",") });
        if (!env.DB?.prepare) fail(503, "NOT_CONFIGURED");
        const db = env.DB.withSession ? env.DB.withSession("first-primary") : env.DB;
        const data = modern ? await v2(req, env, db, url, deps) : await legacy(req, env, db, url, deps);
        if (ctx?.waitUntil && req.method === "POST" && /\/(messages|test|send)$/.test(url.pathname)) {
          ctx.waitUntil(drainBark(env, db, deps, 2).catch(() => {
          }));
        }
        return data instanceof Response ? data : response(data);
      } catch (e) {
        const known = e instanceof ApiError;
        const status = known ? e.status : 503;
        const code = known ? e.code : "TEMPORARILY_UNAVAILABLE";
        return response(modern ? { ok: false, error: { code, message: code, retryable: status >= 500 || status === 429 }, request_id: requestId } : { ok: false, error: code, request_id: requestId }, status, known ? e.headers : {});
      }
    },
    async scheduled(controller, env, ctx) {
      if (!env.DB?.prepare) return;
      const db = env.DB.withSession ? env.DB.withSession("first-primary") : env.DB;
      const work = /* @__PURE__ */ __name(async () => {
        await drainBark(env, db, deps);
        await cleanup(db, deps.now());
        await sweepRecovery(env, db, deps.now());
      }, "work");
      ctx.waitUntil(work().catch(() => console.error("nu_chat_maintenance_failed")));
    }
  };
}
__name(createWorker, "createWorker");
var index_default = createWorker();
export {
  createWorker,
  index_default as default,
  routes
};
//# sourceMappingURL=index.js.map
