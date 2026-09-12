package com.tungsten.fcl.nebula

/**
 * 错误代码表（来自旧版应用，保持编号与说明一致）。
 */
object NebulaConstants {
    const val ERROR01 = "ERROR01"
    const val ERROR02 = "ERROR02"
    const val ERROR03 = "ERROR03"
    const val ERROR05 = "ERROR05"
    const val ERROR06 = "ERROR06"
    const val ERROR07 = "ERROR07"
    const val ERROR08 = "ERROR08"
    const val ERROR10 = "ERROR10"

    val errorDescriptions = mapOf(
        ERROR01 to "找不到游戏目录。请确保游戏版本已正确安装。",
        ERROR02 to "没有文件读写权限。请进入系统「设置 → 应用 → Nebula updater → 权限」，开启「存储」或「所有文件访问」权限。",
        ERROR03 to "网络连接超时。可能原因：\n- 服务器暂时不可达\n- 网络防火墙限制\n- 网络不稳定\n请稍后重试或检查网络环境。",
        ERROR05 to "模组文件校验失败。部分文件下载不完整或 SHA256 不匹配。\n请重新执行更新，若反复出现请联系开发者并提供导出的日志。",
        ERROR06 to "找到了名称相似的文件夹，但不是目标版本文件夹。\n请检查版本目录是否完整。",
        ERROR07 to "NeoForge 版本过低。\n请更新 NeoForge 驱动后再试。",
        ERROR08 to "无法获取更新清单。请确认服务器连接是否正常。",
        ERROR10 to "发生未知错误。请导出日志并联系开发者，日志路径位于应用的 Download 文件夹。"
    )
}