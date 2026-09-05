package com.mio.data

/**
 * Shim: FCL 的渲染器枚举（LaunchOptions 专用）。启动游戏才用得到，安装链只透传。
 */
enum class Renderer {
    GLUT,
    WEBGL,
    VULKAN,
    ZINK
}
