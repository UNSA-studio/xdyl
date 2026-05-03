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
            packageName = "XdylUpdate"
            packageVersion = "1.0.0"
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "Nebula updater"
                // 创建桌面快捷方式
                shortcut = true
                // 创建开始菜单快捷方式
                menu = true
            }
        }
    }
}
