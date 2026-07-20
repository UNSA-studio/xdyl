import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.JavaExec

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)  // 用于设置图标
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
            }
        }
    }
}

tasks.matching { it.name.startsWith("package") }.configureEach {
    if (this is JavaExec) {
        args("--install-dir", "NebulaUpdater")
    }
}
