/**
 * NU 私聊中继（Cloudflare Worker + KV）
 *
 * 域名：nu-pe-chat.cc.cd
 * 存储：KV namespace CHAT_KV
 *   key: <userId>            -> 公钥
 *   msg:<to>:<paddedTs>:<id> -> 密文消息 JSON
 *
 * 认证：调用 NU 主站 /user/profile 校验 access_token
 * 服务器只转发密文，无法解密（客户端 ECDH + AES-GCM）
 */

const AUTH_URL = 'https://login.lanternwaves.fun/user/profile';

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    // CORS 预检
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: cors() });
    }

    if (path === '/chat/ping') {
      return json({ ok: true, ts: Date.now(), runtime: 'worker' });
    }

    const authHeader = request.headers.get('Authorization') || '';
    const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : (url.searchParams.get('token') || '');

    // ---------- 上传公钥 ----------
    if (path === '/chat/key' && request.method === 'POST') {
      const body = await safeJson(request);
      const uid = await verifyToken(token);
      if (!uid) return json({ ok: false, error: 'invalid token' }, 401);
      if (parseInt(body.userId) !== uid) return json({ ok: false, error: 'userId mismatch' }, 403);
      if (!body.publicKey) return json({ ok: false, error: 'missing publicKey' }, 400);
      await env.CHAT_KV.put(String(uid), body.publicKey);
      return json({ ok: true, userId: uid });
    }

    // ---------- 取公钥 ----------
    if (path.startsWith('/chat/key/') && request.method === 'GET') {
      const uid = parseInt(path.split('/')[3]);
      if (!uid) return json({ ok: false, error: 'bad uid' }, 400);
      const pk = await env.CHAT_KV.get(String(uid));
      if (!pk) return json({ ok: false, error: 'user not found' }, 404);
      return json({ ok: true, data: { userId: uid, publicKey: pk } });
    }

    // ---------- 发送 ----------
    if (path === '/chat/send' && request.method === 'POST') {
      const body = await safeJson(request);
      const uid = await verifyToken(token);
      if (!uid) return json({ ok: false, error: 'invalid token' }, 401);
      const from = parseInt(body.from);
      const to = parseInt(body.to);
      if (from !== uid) return json({ ok: false, error: 'from mismatch' }, 403);
      if (!to) return json({ ok: false, error: 'bad to' }, 400);
      const peerKey = await env.CHAT_KV.get(String(to));
      if (!peerKey) return json({ ok: false, error: 'recipient not found' }, 404);
      if (!body.msgId || !body.cipher) return json({ ok: false, error: 'missing fields' }, 400);

      const ts = parseInt(body.ts) || Date.now();
      const msgKey = `msg:${to}:${String(ts).padStart(15, '0')}:${body.msgId}`;
      // 去重：同 msgId 已存在则视为成功
      const exists = await env.CHAT_KV.get(msgKey);
      if (exists) return json({ ok: true, duplicate: true });

      await env.CHAT_KV.put(msgKey, JSON.stringify({
        from, to, msgId: String(body.msgId), cipher: String(body.cipher), ts
      }), { expirationTtl: 60 * 60 * 24 * 30 });   // 保留 30 天

      return json({ ok: true });
    }

    // ---------- 拉取 ----------
    if (path === '/chat/pull' && request.method === 'GET') {
      const uid = await verifyToken(token);
      if (!uid) return json({ ok: false, error: 'invalid token' }, 401);
      const since = parseInt(url.searchParams.get('since') || '0');

      const list = await env.CHAT_KV.list({ prefix: `msg:${uid}:`, limit: 200 });
      const out = [];
      for (const k of list.keys) {
        const parts = k.name.split(':');
        const ts = parseInt(parts[2]);
        if (!(ts > since)) continue;
        const raw = await env.CHAT_KV.get(k.name);
        if (raw) out.push(JSON.parse(raw));
      }
      out.sort((a, b) => a.ts - b.ts);
      return json({ ok: true, data: out });
    }

    // ---------- 已读（占位） ----------
    if (path === '/chat/read' && request.method === 'POST') {
      const uid = await verifyToken(token);
      if (!uid) return json({ ok: false, error: 'invalid token' }, 401);
      return json({ ok: true });
    }

    return json({ ok: false, error: 'not found' }, 404);
  }
};

async function verifyToken(token) {
  if (!token) return null;
  try {
    const r = await fetch(AUTH_URL, {
      headers: { 'Authorization': 'Bearer ' + token, 'User-Agent': 'NU-Chat-Worker/1.0' }
    });
    if (!r.ok) return null;
    const j = await r.json();
    const id = (j && j.data && (j.data.id || j.data.user_id)) || (j && (j.id || j.user_id));
    return id ? parseInt(id) : null;
  } catch (e) {
    return null;
  }
}

async function safeJson(req) {
  try { return await req.json(); } catch (e) { return {}; }
}

function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'content-type,authorization',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'
  };
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...cors() }
  });
}