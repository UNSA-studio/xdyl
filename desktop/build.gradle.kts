import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.JavaExec

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
            packageName = "NebulaUpdater"      // 显示名称、安装文件夹名称
            packageVersion = "1.0.0"
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "NebulaUpdater"
                shortcut = true
                menu = true
            }
        }
    }
}

// 为 packageMsi 任务添加自定义 jpackage 参数（仅在 Windows 环境下生效）
tasks.matching { it.name.startsWith("package") }.configureEach {
    if (this is JavaExec) {
        // --install-dir 设置默认安装根目录下的子文件夹名，例如 C:\Users\...\AppData\Local\NebulaUpdater
        args("--install-dir", "NebulaUpdater")
    }
}
