# NU 私聊 —— 架构与密钥恢复设计

> 现状：**Android 走 v1（legacy），iOS 保持 v2**。平台的「用哪个节点」由服务端
> 说了算。服务端正式源码在 `cloudflare/nu-chat-worker/`，实际部署自 VPS 上的
> 副本 `/root/nu-chat/backend/nu-chat-worker`（`npx wrangler deploy -c wrangler.production.jsonc`）。

## 一、两套协议，一份数据

Android 说 **v1（legacy）**，iOS 说 **v2**，两者打到同一个 Worker：

```
Android →  https://nu-pe-chat.cc.cd/Android/chat/…   → 走 legacy.js
iOS     →  https://nu-pe-chat.cc.cd/iOS/chat/v2/…    → 走 v2.js
```

`/Android/`、`/iOS/` 这两个前缀在 fetch 入口（`index.js`）就被剥掉，所以两套
协议共用一套路由。数据都写进同一批 D1 表，键是 `subject`（由主站账号 ID
派生），因此**某人在 Android 上登记过，iOS 就能找到他；反过来也一样**——两端
都不必去兼容对方的协议。这就是当初「协议不同、数据互通、转换在服务端做」的
设计。

### GET 伪装成 POST（`__alias`）

某些网络会直接 RST 明文 GET，客户端可改发 `POST /…?__alias=1`，Worker 在路由
之前把它还原成真正的动词。实现见 `index.js`：

```js
req = new Request(url.toString(), { method: 'GET', headers: req.headers });
```

**不要**「简化」回 `new Request(req, { method: 'GET' })`——在 Workers 里克隆
一个带 body 的 POST 成 GET 会抛异常，结果是所有 POST 都返回 1101。

## 二、首次启动的设备登记

目的：新装的应用必须知道「我该连哪个私聊节点」，而且**重装之后要立刻被认
出来，不需要用户做任何操作**。

```
device_code = SHA-256("nu-device-v1" | user_id | platform)   // 64 位 hex
```

- 由主站账号 ID（`my_user_id`）派生，**不落盘**。
- 同一账号 ⇒ 同一设备码 ⇒ 重装算出来的码不变，服务端查到已有行就直接回话。
- 登记属于「顺手做的事」：`MainActivity` 刷新资料时会起一个后台线程调
  `ChatDeviceRegistry.ensureRegistered()`：

```
POST /Android/chat/device/register
     { device_code, platform: "android", app_version }
  →  { ok, data: { chat_base, entry_prefix, protocol, … } }
```

- 服务端写入/刷新 `device_registry(subject, device_code, …)`。
- 返回的 `chat_base` 会写回 `chat_server` 偏好，之后所有私聊流量都走它。
- 幂等：本地用一个偏好位拦住重复请求；登出时 `ChatDeviceRegistry.reset`
  清掉标志，换号后会重新登记。
- 登记失败（离线、或者老系统没有 ECH 栈被 RST）不影响使用——内置默认地址
  本来就是对的，下次刷新资料再试。

**一个要清楚的取舍**：设备码由账号派生，所以同一账号在两台手机上算出来的
码相同，登记表里算同一台设备。要做到「每台物理设备独立」，设备码就必须本地
随机生成并持久化，但那样重装就换码，与「重装秒认」矛盾。当前选择的是
**重装秒认**。

## 三、私钥备份（B1 + B3）

端到端加密不降级：**服务器从头到尾只拿到密文**。

```
payload = [u32 私钥长度][u32 公钥长度][PKCS#8 私钥][SPKI 公钥]

KEK  = PBKDF2-HMAC-SHA256(secret, salt, 210000 次, 256 位)
blob = base64(salt[16] | nonce[12] | AES-256-GCM(KEK, payload))
```

每个用户上传两份密文（`PUT /chat/key-backup`）：

| 密文 | 用什么加密 | 用途 |
|---|---|---|
| `pwd_blob`  | 登录密码 | 日常恢复（B1） |
| `code_blob` | 恢复码（20 位，字符集去掉了 0/O/1/I/L） | 忘记密码时兜底（B3） |

服务端另外存 `code_hash = SHA-256(规整化后的恢复码)`，只用于「忘记密码」时
定位记录（`POST /chat/key-backup/lookup`），它本身不能反推出恢复码。

恢复逻辑（`ChatKeyBackupService`）：

- `restoreWithPassword(ctx, password)` —— 取备份、解密 `pwd_blob`、装回密钥、
  重新登记公钥。
- `restoreWithCode(ctx, code)` —— 规整输入（转大写、去分隔符）、取哈希、查表、
  解密 `code_blob`、装回密钥。

恢复码**只在首次备份时展示一次**（弹窗 + 自动复制到剪贴板）；重复备份沿用
已有恢复码，不会让已经抄下来的码悄悄失效。

## 四、弹窗规范

所有面向用户的弹窗都走 `AppDialogs`（`MaterialAlertDialogBuilder` +
`R.style.DialogAnimation`），现有 `tip`、`confirm`、`choose`，以及补齐的
`input`（单行输入，支持密码模式）。

**不要**直接 new `androidx.appcompat.app.AlertDialog.Builder`——密钥备份那几个
弹窗当初就是这么写的，结果没有走应用的深色主题和转场动画，已在提交
`8efc967` 里全部迁移。

## 五、部署

```bash
# 在 VPS 上（正式源码 + wrangler 授权都在那儿）
cd /root/nu-chat/backend/nu-chat-worker

# 改表结构：新增 migrations/00NN_*.sql 后执行
npx wrangler d1 migrations apply nu-chat-db --remote -c wrangler.production.jsonc

# 发布
npx wrangler deploy -c wrangler.production.jsonc
```

部署后的绑定：`DB`（D1 `nu-chat-db`）、`BARK_ENABLED`、
`BARK_ENCRYPTION_KEY`（secret）、`CURSOR_SECRET`（secret）。secret 不会因为
重新部署而丢失，不需要重新录入。

仓库里的 `cloudflare/nu-chat-worker/` 是 VPS 源码的镜像。改服务端代码时改哪
边都行，但**真正部署的是 VPS 那份**，改完记得两边同步。

## 六、当前限制与已知问题

- 这台测试用的 Android 是 SDK 29，没有 ECH 网络栈，直连 `nu-pe-chat.cc.cd`
  会被 RST；客户端有端点轮换和 `__alias` 逃生通道，正常情况下仍能连上，
  但首次登记可能需要重试。
- 密钥轮换之前的旧消息解不开，这是端到端加密的物理事实，客户端会渲染成
  「无法解密」的错误卡片（带「核对密钥」入口）。
- **iOS 客户端**的恢复码界面由 iOS 自己的组件渲染，本仓库管不到它的样式。