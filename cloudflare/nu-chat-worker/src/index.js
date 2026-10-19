/**
 * NU 私聊中继 v2（Cloudflare Worker + D1）
 *
 * 相比 v1（KV 版）的改进：
 *   1. 消息永久保存 —— 无 TTL、无 30 天过期
 *   2. 无条数上限 —— SQL 分页，客户端可用 has_more/next_cursor 循环拉全量
 *   3. seq 游标 —— 服务端自增序号，同毫秒消息不会互相覆盖，不漏信
 *   4. 双向同步 —— 收件箱 + 自己发出的消息，多设备能看到完整对话
 *   5. 强幂等 —— UNIQUE(from_user, msg_id)，并发/重试不会重复
 *   6. 必须 Bearer —— 不再接受 JSON/query 里的 token，身份只来自身份验证
 *
 * 路由（全部为 /chat/*）：
 *   GET  /chat/ping                            健康检查 + 存储模式
 *   POST /chat/key        Bearer              登记本机公钥
 *   GET  /chat/key/{user}                     取对端公钥（匿名）
 *   POST /chat/send       Bearer              发送密文（幂等）
 *   GET  /chat/pull       Bearer?cursor=&limit=  按 seq 增量拉取（含自己发的）
 *   POST /chat/read       Bearer              记录已读水位
 */

const AUTH_URL = 'https://login.lanternwaves.fun/user/profile';
const MAX_LIMIT = 500;
const DEFAULT_LIMIT = 200;

function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization,Content-Type',
    'Cache-Control': 'no-store'
  };
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...cors() }
  });
}

async function readJson(req) {
  try {
    return await req.json();
  } catch (e) {
    return null;
  }
}

/** 校验主站 access token，返回 username 或 null。只认 Authorization 头。 */
async function verify(req) {
  const h = req.headers.get('Authorization') || '';
  if (!h.toLowerCase().startsWith('bearer ')) return null;
  const token = h.slice(7).trim();
  if (!token) return null;
  try {
    const r = await fetch(AUTH_URL, {
      headers: { Authorization: 'Bearer ' + token, 'User-Agent': 'NU-Chat/2.0' }
    });
    if (!r.ok) return null;
    const j = await r.json();
    const d = j && (j.data || j);
    const u = d && (d.username || d.nickname);
    return u ? String(u).trim() : null;
  } catch (e) {
    return null;
  }
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const path = url.pathname;

    if (req.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: cors() });
    }

    try {
      // ---------- 健康检查 ----------
      if (path === '/chat/ping') {
        return json({
          ok: true,
          ts: Date.now(),
          mode: 'd1',
          retention: 'forever',
          limits: 'none'
        });
      }

      // ---------- 登记公钥 ----------
      if (path === '/chat/key' && req.method === 'POST') {
        const me = await verify(req);
        if (!me) return json({ ok: false, error: 'invalid token' }, 401);

        const b = (await readJson(req)) || {};
        const userId = String(b.userId || b.username || me).trim();
        const publicKey = String(b.publicKey || '').trim();
        if (!publicKey) return json({ ok: false, error: 'missing publicKey' }, 400);
        if (userId && userId !== me) return json({ ok: false, error: 'username mismatch' }, 403);

        await env.DB.prepare(
          'INSERT INTO users (username, public_key, updated_at) VALUES (?1, ?2, ?3) ' +
          'ON CONFLICT(username) DO UPDATE SET public_key = excluded.public_key, updated_at = excluded.updated_at'
        ).bind(me, publicKey, Date.now()).run();

        return json({ ok: true, userId: me });
      }

      // ---------- 取对端公钥 ----------
      if (path.startsWith('/chat/key/')) {
        const u = decodeURIComponent(path.slice('/chat/key/'.length)).trim();
        if (!u) return json({ ok: false, error: 'bad user' }, 400);
        const row = await env.DB.prepare(
          'SELECT username, public_key FROM users WHERE username = ?1'
        ).bind(u).first();
        if (!row) return json({ ok: false, error: 'user not found' }, 404);
        return json({ ok: true, data: { userId: row.username, publicKey: row.public_key } });
      }

      // ---------- 发送 ----------
      if (path === '/chat/send' && req.method === 'POST') {
        const me = await verify(req);
        if (!me) return json({ ok: false, error: 'invalid token' }, 401);

        const b = (await readJson(req)) || {};
        const to = String(b.to || '').trim();
        const msgId = String(b.msgId || '').trim();
        const cipher = String(b.cipher || '').trim();
        const from = String(b.from || me).trim();

        if (!to || !msgId || !cipher) return json({ ok: false, error: 'missing fields' }, 400);
        if (from !== me) return json({ ok: false, error: 'from mismatch' }, 403);

        const rcpt = await env.DB.prepare('SELECT username FROM users WHERE username = ?1').bind(to).first();
        if (!rcpt) return json({ ok: false, error: 'recipient not found' }, 404);

        const ts = parseInt(b.ts, 10) || Date.now();
        const now = Date.now();

        const ins = await env.DB.prepare(
          'INSERT INTO messages (msg_id, from_user, to_user, cipher, ts, created_at) ' +
          'VALUES (?1, ?2, ?3, ?4, ?5, ?6) ' +
          'ON CONFLICT(from_user, msg_id) DO NOTHING'
        ).bind(msgId, me, to, cipher, ts, now).run();

        const changed = (ins.meta && ins.meta.changes) || 0;
        const row = await env.DB.prepare(
          'SELECT seq FROM messages WHERE from_user = ?1 AND msg_id = ?2'
        ).bind(me, msgId).first();

        if (changed === 0) {
          return json({ ok: true, duplicate: true, seq: row ? row.seq : null });
        }
        return json({ ok: true, duplicate: false, seq: row ? row.seq : null });
      }

      // ---------- 拉取（双向，seq 游标，可分页） ----------
      if (path === '/chat/pull') {
        const me = await verify(req);
        if (!me) return json({ ok: false, error: 'invalid token' }, 401);

        let cursor = parseInt(url.searchParams.get('cursor') || url.searchParams.get('since') || '0', 10);
        if (!isFinite(cursor) || cursor < 0) cursor = 0;
        // 兼容旧客户端：老版传的是毫秒时间戳，当作从头开始
        if (cursor > 1e12) cursor = 0;

        let limit = parseInt(url.searchParams.get('limit') || String(DEFAULT_LIMIT), 10);
        if (!isFinite(limit) || limit < 1) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        const rs = await env.DB.prepare(
          'SELECT seq, msg_id, from_user, to_user, cipher, ts FROM messages ' +
          'WHERE (to_user = ?1 OR from_user = ?1) AND seq > ?2 ' +
          'ORDER BY seq ASC LIMIT ?3'
        ).bind(me, cursor, limit + 1).all();

        const rows = (rs.results || []);
        const hasMore = rows.length > limit;
        const page = hasMore ? rows.slice(0, limit) : rows;
        const next = page.length ? page[page.length - 1].seq : cursor;

        return json({
          ok: true,
          data: page.map(function (r) {
            return {
              seq: r.seq,
              msgId: r.msg_id,
              from: r.from_user,
              to: r.to_user,
              cipher: r.cipher,
              ts: r.ts
            };
          }),
          next_cursor: next,
          has_more: hasMore,
          server_time_ms: Date.now()
        });
      }

      // ---------- 已读水位 ----------
      if (path === '/chat/read' && req.method === 'POST') {
        const me = await verify(req);
        if (!me) return json({ ok: false, error: 'invalid token' }, 401);

        const b = (await readJson(req)) || {};
        const peer = String(b.peerId || '').trim();
        const upto = parseInt(b.upto, 10) || 0;
        if (!peer) return json({ ok: false, error: 'missing peerId' }, 400);

        await env.DB.prepare(
          'INSERT INTO reads (reader, peer, upto_seq, updated_at) VALUES (?1, ?2, ?3, ?4) ' +
          'ON CONFLICT(reader, peer) DO UPDATE SET upto_seq = MAX(reads.upto_seq, excluded.upto_seq), updated_at = excluded.updated_at'
        ).bind(me, peer, upto, Date.now()).run();

        return json({ ok: true });
      }

      return json({ ok: false, error: 'not found' }, 404);
    } catch (e) {
      // 不回传堆栈，避免内部信息泄露
      return json({ ok: false, error: 'server error' }, 500);
    }
  }
};
