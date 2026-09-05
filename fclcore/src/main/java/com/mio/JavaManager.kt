package com.mio

import com.tungsten.fclcore.game.Version

/**
 * Shim: FCL 的 Java 运行时管理器。星云更新器不启动游戏，仅提供合适的 JRE 名。
 * 后续接入真实 JRE 下载时替换实现。
 */
object JavaManager {
    @JvmStatic
    fun getSuitableJavaVersion(version: Version?): com.tungsten.fclcore.game.JavaVersion {
        // MC 1.21.x 需要 Java 21；保守回退 17
        return com.tungsten.fclcore.game.JavaVersion(false, "21", "jre21")
    }
}
