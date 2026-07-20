import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
}

compose.desktop {
    application {
        mainClass = "www.xdyl.hygge.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "NebulaUpdater"
            packageVersion = "1.0.0"
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "NebulaUpdater"
                shortcut = true
                menu = true
                // 添加 jpackage 参数，固定安装目录并创建快捷方式
                jpackageArgs += listOf(
                    "--win-shortcut",
                    "--install-dir", "NebulaUpdater"  // 安装到用户选择的目录下的 NebulaUpdater 子文件夹
                )
            }
        }
    }
}
