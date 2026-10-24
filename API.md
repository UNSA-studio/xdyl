# 星灯云浪 / Nebula Updater 服务端 API 清单

> 来源：客户端代码 + 反编译（ModUpdater2.dll / StarWave-Android）+ 实测
> 最后更新：2026-10-18

## 基础信息

| 项 | 值 |
|---|---|
| 主站 Base | `https://login.lanternwaves.fun` |
| 模组源 | `http://api.lanternwaves.fun:5551/mods/` |
| 版本清单 | `https://unsa-fdws.cc.cd/api/download/Version_difference.json`（需 `X-API-Key`） |
| 市场/交易 | `market.lanternwaves.fun`（已从 DLL 发现，接口未接入） |
| MC 服务器 | `mc.lanternwaves.fun:25565` |
| 认证方式 | `Authorization: Bearer <access_token>` |
| 返回格式 | `{"code":200,"data":{...}}` / `{"code":4xx,"message":"..."}` |

### 令牌

| 令牌 | 有效期 | 说明 |
|---|---|---|
| `access_token` | 约 1 小时 | JWT，payload 含 `user_id`/`type`/`version`/`exp` |
| `refresh_token` | 约 30 天 | 用于续期 |

---

## 一、认证

### POST /login
```json
{"username":"...", "password":"..."}
```
返回 `data.access_token` / `data.refresh_token` / `data.nickname`

### POST /register
注册（参数未完整确认）

### POST /refresh
```json
{"refresh_token":"..."}
```
返回新的 `access_token` + `refresh_token`

### POST /send-verify-code
找回密码：发送验证码（参数待确认）

### POST /reset-password
找回密码：重置（参数待确认）

### GET /check-qq-login?session_id=xxx
QQ 登录轮询，返回 token

---

## 二、用户

| 方法 | 路径 | 说明 | 状态 |
|---|---|---|---|
| GET | `/user/profile` | 当前用户资料 | ✅ 已用 |
| POST | `/user/avatar` | 更新头像 | ✅ 已用 |
| POST | `/user/change-username` | 改昵称 | ✅ 已用 |
| POST | `/user/password` | 改密码 | ✅ 已用 |
| POST | `/user/bind-qq` | 绑定 QQ | ✅ 已用 |
| GET | `/user/items` | 我的物品 | ✅ 已用 |
| GET | `/user/game-bindings` | 游戏账号绑定列表 | ✅ 已用 |
| POST | `/user/game-bindings/delete` | 解除绑定 | ✅ 已用 |

### GET /user/profile 返回样例
```json
{"code":200,"data":{
  "id":21,"nickname":"OvalXD_chen","username":"OvalXD_chen",
  "avatar":"","bio":"","coins":13,"exp":50,"level":1,"next_level_exp":50,
  "playtime":6143,"points":0,"qq_bound":true,"role":"user",
  "theme_color":"#4a90d9","title":"","email":"1531219200@qq.com",
  "created_at":"2026-09-25T02:43:19.932179",
  "last_login":"2026-10-18T12:37:28.140240"
}}
```

> ⚠️ **`/user/profile?id=xx` 会忽略 `id`，永远返回自己**。这就是「无法用 ID 查玩家名」的原因。

---

## 三、论坛

| 方法 | 路径 | 说明 | 状态 |
|---|---|---|---|
| GET | `/forum/posts?page=1` | 帖子列表 | ✅ |
| GET | `/forum/post/{id}` | 帖子详情 | ✅ |
| POST | `/forum/post` | **发帖**（注意是单数） | ✅ |
| POST | `/forum/post/{id}/reply` | 回复 | ✅ |
| POST | `/forum/post/{id}/like` | 点赞（toggle） | ✅ |
| POST | `/forum/post/{id}/tip` | 打赏 | ✅ |
| GET | `/forum/categories` | 版块列表 | ✅ |

### GET /forum/posts 返回样例
```json
{"code":200,"data":{"page":1,"per_page":20,"posts":[{
  "id":2,"title":"测试帖子","content":"测试内容",
  "nickname":"wonder114514","username":"wonder114514",
  "avatar":"avatar_1_1786432618.jpg","user_title":"xyz",
  "likes":3,"views":175,"reply_count":19,
  "is_pinned":1,"is_essence":0,
  "created_at":"2026-08-11 12:27:29","updated_at":"2026-08-11 12:27:29"
}]}}
```

> ⚠️ **帖子数据里也没有 `user_id`**，只有 `nickname` / `username`

### POST /forum/post 参数（实测要求三个字段）
```json
{"category_id":1, "title":"...", "content":"..."}
```
`/forum/posts`（复数）发帖返回 **405**，只支持 GET

---

## 四、称号

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/titles/mine` | 我的称号 |
| GET | `/titles/catalog` | 称号商店 |
| POST | `/titles/wear` | 佩戴 |
| POST | `/titles/buy` | 购买 |

---

## 五、任务 / 时长奖励

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/tasks` | 任务列表 |
| POST | `/tasks/{id}/claim` | 领取任务 |
| GET | `/playtime/rewards` | 时长奖励 |
| POST | `/playtime/rewards/claim` | 领取（参数是 `{"hours":N}`，**不是 reward_id**） |

### GET /playtime/rewards 结构
```json
{"code":200,"data":{
  "total_seconds": 6100,
  "milestones": [{"hours":1,"coins":20,"exp":30,"reached":true,"claimable":false,"claimed":true}]
}}
```

### GET /tasks 结构
字段：`id` / `title` / `description` / `is_completed` / `claimed` / `progress` / `target_count` / `target_action` / `reward_coins`

---

## 六、商城 / 兑换 / 打赏

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/shop/items` | 商品列表（含 `price`） |
| POST | `/shop/buy` | 购买 `{item_id, quantity}` |
| GET | `/redeem/rate` | 兑换比例 |
| POST | `/redeem/game-coins` | 兑换游戏币 `{amount}` |

---

## 七、市场行情

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/market/preview` | 行情列表（118 个物品，含买价/涨跌） |

---

## 八、投票 / 周目 / 意见箱

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/polls` | 投票列表 |
| POST | `/polls/{id}` | 投票 |
| GET | `/seasons` | 周目列表 |
| GET | `/suggestions` | 意见箱列表 |
| POST | `/suggestions` | 提交意见 |

---

## 九、决斗场 PvP

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/pvp/me` | 我的排位（`rank_position`/`rating`/`peak_rating`/`tier`/`next_tier`/`wins`/`losses`/`matches`） |
| GET | `/pvp/rank` | 排行榜（`name`/`rank`/`rating`/`tier`/`wins`/`losses`/`matches`） |
| GET | `/pvp/online` | 在线对手 |
| GET | `/pvp/incoming` | 收到的挑战 |
| GET | `/pvp/challenge` | 发起挑战 |
| POST | `/pvp/challenge/respond` | 应战 |
| POST | `/pvp/match/join` | 加入匹配 |
| GET | `/pvp/match/status` | 匹配状态（`{"state":"idle"}`） |
| GET | `/pvp/history` | 近期对局 |

---

## 十、通知 / 纪念堂 / 在线玩家

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/notifications?limit=5` | 通知列表 |
| POST | `/notifications/read` | 标记已读 |
| GET | `/memorials` | 纪念堂（公开） |
| GET | `/server/players` | 在线玩家（`count` + `players[{name,username,avatar}]`） |

---

## 十一、爱发电

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/afdian/status` | 查询绑定状态（**POST**，不是 GET） |
| POST | `/afdian/claim` | 领取 |

---

## 十二、上传

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/upload/image` | 图片上传（multipart，字段 `file`） |

---

## 十三、从反编译发现、但**未确认/未接入**的接口

| 路径 | 推测用途 | 状态 |
|---|---|---|
| `/history?player=` | 玩家历史记录 | ❌ 实测 **404**（路径可能不对） |
| `/pay/create` | 创建支付订单 | ⚠️ 空响应，需参数 |
| `/vote` | 投票（另一套？） | ⚠️ 与 `/polls` 并存，未确认 |
| `/mods/list` | 模组清单（77 个） | ✅ 可用，但**未接入 UI** |
| `/update/latest` | 整合包最新版本 | 未接入 |
| `/update/pack-files` | 服务端修复包（`serverfix3.zip` 246MB） | 未接入 |
| `/update/pack-links` | 整合包下载链接 | 未接入 |
| `market.lanternwaves.fun` 全部接口 | 市场/交易系统 | ❌ 完全未知 |
| `api.lanternwaves.fun:8080` | YSM 皮肤服务（Flask） | ❌ 路径未知 |

---

## 十四、自建私聊服务（不在官方 API 内）

> 这是我们自己加的，与服主服务端无关

| 项 | 值 |
|---|---|
| Worker 地址 | `http://nu-pe-chat.cc.cd`（CF，已关强制 HTTPS） |
| VPS 备用 | `http://160.202.237.166:8090`（nginx → 8093） |
| 音乐包（每日歌曲） | `http://160.202.237.166:8090/music_pack.7z`（Python SimpleHTTP，目录 `/var/www/music`，**不支持 Range**，客户端需回退全量） |
| 存储 | CF KV `nu-chat-kv` / VPS JSON |
| 加密 | X25519 ECDH + AES-256-GCM（端到端，服务器只见密文） |

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/chat/ping` | 健康检查 |
| POST | `/chat/key` | 上传公钥 `{userId, publicKey}` + Bearer token |
| GET | `/chat/key/{uid}` | 取对端公钥（不存在返回 404） |
| POST | `/chat/send` | 发送 `{from, to, msgId, cipher, ts}` |
| GET | `/chat/pull?since=ms` | 拉取消息 |
| POST | `/chat/read` | 标记已读 |

---

## 已知问题汇总

1. **无法用 user_id 反查玩家名**：`/user/profile?id=` 忽略参数；论坛/排行榜/在线玩家都不带 `id`
2. **`/history?player=` 404**：路径可能不是这个
3. **`/pay/create` 空响应**：需要参数语义
4. **CF 的 HTTPS 在此网络被 RST**：已通过「强制 HTTPS 关闭 + 客户端 HTTP 回退」绕过

---

## 平台分入口（2026-10-20 起）

中继服务端按平台把入口分开，两边互不干扰：

```
Android → https://nu-pe-chat.cc.cd/Android/chat/...
iOS     → https://nu-pe-chat.cc.cd/iOS/chat/v2/...
```

服务端会把 `/Android` 或 `/iOS` 前缀剥掉，再按同一套路由处理；
不带前缀的旧路径（`/chat/...`）仍然可用，兼容旧客户端。

### Android 使用的接口（legacy，纯文字 + 单密钥）

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| GET | `/Android/chat/ping` | 匿名 | 健康检查 |
| POST | `/Android/chat/key` | Bearer | 登记本机公钥（DER SPKI） |
| GET | `/Android/chat/key/{username}` | 匿名 | 取对端公钥 |
| POST | `/Android/chat/send` | Bearer | 发送密文（按 `(from,msgId)` 幂等） |
| GET | `/Android/chat/pull?cursor=&limit=` | Bearer | 按 `seq` 增量拉取（含自己发的） |
| POST | `/Android/chat/read` | Bearer | 记录已读水位 |

### iOS 使用的能力（v2，Android 不实现也不调用）

| 能力 | 路径前缀 | 说明 |
|---|---|---|
| 能力发现 | `/iOS/chat/v2/capabilities` | 返回协议版本、算法、features |
| 设备会话 | `/iOS/chat/v2/session` | 短期聊天令牌（主站 token 只在此用一次） |
| 设备与密钥 | `/iOS/chat/v2/devices[/{id}/keys]` | 每设备两把公钥（X25519 + P256）、CAS 版本 |
| 发送 | `/iOS/chat/v2/messages` | 每设备 envelope，含自副本 |
| 增量同步 | `/iOS/chat/v2/sync` + `/sync/ack` | 每设备 `event_seq` 事件流 |
| 已读 | `/iOS/chat/v2/conversations/{id}/read` | 会话级已读水位 |
| 通知 | `/iOS/chat/v2/devices/{id}/bark*` | Bark 推送绑定与状态 |

### 为什么这样分

- **安卓不需要设备级多密钥**：单机单密钥够用，换成多设备 envelope 反而复杂。
- **安卓不需要 Bark**：Android 有自己的后台/通知，不依赖 APNs。
- **回执、分页游标签名、历史备份**这些 iOS 侧可靠性能力，对安卓是额外负担。
- 分开后两边各自演进，改 iOS 的协议不会碰坏 Android 的 legacy 通道。

### 保留策略（当前）

```
retention_days = 365000   （≈ 1000 年，实际等于永久）
定时清理任务 = 已停用
```

原来每分钟跑一次的 `cleanup()`（会删除 30 天前的消息）已经移除，
新写入的消息 `expires_at` 也被改为 1000 年后。
