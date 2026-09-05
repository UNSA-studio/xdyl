# fclcore 模块说明

本目录下的 `fclcore` 模块包含从 [Fold Craft Launcher (FCL)](https://github.com/FCL-Team/FoldCraftLauncher)
vendored 而来的整合包安装核心代码（最小依赖闭包，约 330 个源文件）。

- 原始代码版权 (C) FCL-Team & contributors（血统源自 Hello Minecraft! Launcher）
- 原项目许可证：**GNU GPL-3.0**
- vendored 时间：2026-09-05，基于上游 commit `c05ef56`
- 包名保持 `com.tungsten.fclcore` 原样，便于跟随上游同步
- 本模块随整个项目以 **GPL-3.0** 分发（见根目录 LICENSE）

## 已做的适配（相对上游的改动）

| 适配点 | 说明 |
|--------|------|
| `com.tungsten.fcl.FCLApp` | Shim：仅提供 `getAppContext()`，由星云更新器注入 Context |
| `com.tungsten.fcl.R` | Shim：资源 ID 转发到本模块的 `com.tungsten.fclcore.R` |
| `com.tungsten.fclauncher.utils.FCLPath` | Shim：路径表指向星云更新器自己的外部存储目录 |
| `com.mio.data.Renderer` / `com.mio.JavaManager` | Shim：渲染器/Java 管理器桩实现 |
| 删除 `download/ProcessService.kt` | 该类依赖 FCL 完整启动链（FCLauncher/FCLBridge），安装链不需要 |
| 加入上游 `ZipFileSystem` 模块 | 作为 `com.sun.nio.zipfs` 内联实现 |

## 功能范围（我们实际使用的部分）

- `mod.mcbbs.McbbsModpackLocalInstallTask` — MCBBS/中文打包规范 zip 安装
- `mod.ModpackInstallTask` / `ModpackUpdateTask` — 通用安装/更新任务
- `download.DefaultDependencyManager` — 依赖解析与缺失库补全下载
- `game.DefaultGameRepository` — 版本仓库管理
- `util.io.Unzipper` — 带进度回调的解压
- `task.*` — fclcore 异步任务体系

未搬移：启动器（launch/auth/Terracotta/LWJGL 渲染层）、GUI、账号系统。
