# Nebula Updater（星云更新器）

星灯云浪服务器（`login.lanternwaves.fun`）的 Android 客户端 + 桌面端。

## 功能

- 整合包 / 模组一键更新（CSV 与 `mods.json` 双源、SHA-256 校验）
- 社区：公告、论坛（发帖 / 回复 / 点赞 / 打赏 / 图片）、排行榜、在线玩家、纪念堂
- 账号：登录 / 注册 / QQ 绑定 / 改昵称 / 改密码 / 头像 / 找回密码
- 商城：商品、称号（含自定义称号）、兑换码、喵币兑换
- 游戏数据：任务中心、时长奖励、决斗场（PVP）、投票、周目、意见箱
- 市场行情、爱发电、通知、YSM 皮肤（上传 / 历史）
- 私聊：端到端加密（X25519 ECDH + AES-256-GCM），服务器只见密文
- 音频播放器（Media3 ExoPlayer + LRC 歌词，支持字级时间戳）

## 构建

```bash
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

CI：GitHub Actions（`.github/workflows/android.yml`），push 到 `main` 自动构建并上传 artifact。

## 目录

```
app/            Android 客户端
desktop/        桌面端（Compose）
cloudflare/     私聊中继 Worker（Cloudflare KV）
API.md          服务端接口清单（实测字段）
```

## License / 开源协议

本项目以 **GNU General Public License v3.0 (GPL-3.0)** 发布，完整文本见仓库根目录 [`LICENSE`](LICENSE)。

This project is licensed under the **GNU General Public License v3.0**. See [`LICENSE`](LICENSE) for the full text.

> 说明：项目内含以 GPL-3.0 分发的第三方代码（`fclcore`，来自 [FCL-Team/FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher)），因此整体采用 GPL-3.0 兼容授权。
