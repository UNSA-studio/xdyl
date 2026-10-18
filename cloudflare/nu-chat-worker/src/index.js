/**
 * NU 私聊中继（Cloudflare Worker + KV）—— username 版
 *
 * 与 VPS 版协议一致：所有身份标识都用主站 username（不是数字 id）。
 * 原因：主站 /player/info/{player} 的键是 username，数字 id 查不到名字。
 *
 * KV 结构：
 *   key:<username>                 -> 公钥
 *   msg:<to>:<paddedTs>:<msgId>    -> 密文消息
 */

const AUTH_URL = 'https://login.lanternwaves.fun/user/profile';

addEventListener('fetch', function (event) {
  event.respondWith(handle(event.request));
});

async function handle(request) {
  try {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors() });
    if (path === '/chat/ping') return json({ ok: true, ts: Date.now(), mode: 'username' });

    const authHeader = request.headers.get('Authorization') || '';
    const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : (url.searchParams.get('token') || '');

    // ---------- 上传公钥 ----------
    if (path === '/chat/key' && request.method === 'POST') {
      const body = await safeJson(request);
      const who = await verifyToken(token);
      if (!who) return json({ ok: false, error: 'invalid token' }, 401);
      const uname = String(body.userId || body.username || who.username);
      if (uname !== who.username) return json({ ok: false, error: 'username mismatch' }, 403);
      if (!body.publicKey) return json({ ok: false, error: 'missing publicKey' }, 400);
      await CHAT_KV.put('key:' + uname, body.publicKey);
      return json({ ok: true, userId: uname });
    }

    // ---------- 取公钥 ----------
    if (path.indexOf('/chat/key/') === 0 && request.method === 'GET') {
      const uname = decodeURIComponent(path.split('/')[3] || '');
      if (!uname) return json({ ok: false, error: 'bad user' }, 400);
      const pk = await CHAT_KV.get('key:' + uname);
      if (!pk) return json({ ok: false, error: 'user not found' }, 404);
      return json({ ok: true, data: { userId: uname, publicKey: pk } });
    }

    // ---------- 发送 ----------
    if (path === '/chat/send' && request.method === 'POST') {
      const body = await safeJson(request);
      const who = await verifyToken(token);
      if (!who) return json({ ok: false, error: 'invalid token' }, 401);
      const from = String(body.from || who.username);
      const to = String(body.to || '');
      if (from !== who.username) return json({ ok: false, error: 'from mismatch' }, 403);
      if (!to) return json({ ok: false, error: 'bad to' }, 400);
      const peerKey = await CHAT_KV.get('key:' + to);
      if (!peerKey) return json({ ok: false, error: 'recipient not found' }, 404);
      if (!body.msgId || !body.cipher) return json({ ok: false, error: 'missing fields' }, 400);

      const ts = parseInt(body.ts) || Date.now();
      const msgKey = 'msg:' + to + ':' + String(ts).padStart(15, '0') + ':' + body.msgId;
      const exists = await CHAT_KV.get(msgKey);
      if (exists) return json({ ok: true, duplicate: true });

      await CHAT_KV.put(msgKey, JSON.stringify({
        from: from, to: to, msgId: String(body.msgId), cipher: String(body.cipher), ts: ts
      }), { expirationTtl: 2592000 });
      return json({ ok: true });
    }

    // ---------- 拉取 ----------
    if (path === '/chat/pull' && request.method === 'GET') {
      const who = await verifyToken(token);
      if (!who) return json({ ok: false, error: 'invalid token' }, 401);
      const since = parseInt(url.searchParams.get('since') || '0');
      const list = await CHAT_KV.list({ prefix: 'msg:' + who.username + ':', limit: 200 });
      const out = [];
      for (let i = 0; i < list.keys.length; i++) {
        const k = list.keys[i];
        const ts = parseInt(k.name.split(':')[2]);
        if (!(ts > since)) continue;
        const raw = await CHAT_KV.get(k.name);
        if (raw) out.push(JSON.parse(raw));
      }
      out.sort(function (a, b) { return a.ts - b.ts; });
      return json({ ok: true, data: out });
    }

    if (path === '/chat/read' && request.method === 'POST') {
      const who = await verifyToken(token);
      if (!who) return json({ ok: false, error: 'invalid token' }, 401);
      return json({ ok: true });
    }

    return json({ ok: false, error: 'not found' }, 404);
  } catch (e) {
    return json({ ok: false, error: String(e && e.message || e), stack: String(e && e.stack || '') }, 500);
  }
}

async function verifyToken(token) {
  if (!token) return null;
  try {
    const r = await fetch(AUTH_URL, { headers: { 'Authorization': 'Bearer ' + token, 'User-Agent': 'NU-Chat/1.0' } });
    if (!r.ok) return null;
    const j = await r.json();
    const d = (j && j.data) ? j.data : j;
    const id = d.id || d.user_id;
    const uname = d.username || d.nickname || (id ? String(id) : '');
    if (!uname) return null;
    return { id: id ? parseInt(id) : 0, username: String(uname) };
  } catch (e) {
    return null;
  }
}

async function safeJson(req) { try { return await req.json(); } catch (e) { return {}; } }
function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'content-type,authorization',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'
  };
}
function json(obj, status) {
  const st = status || 200;
  return new Response(JSON.stringify(obj), {
    status: st,
    headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, cors())
  });
}