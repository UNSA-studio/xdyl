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
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("com.github.junrar:junrar:7.5.5")
}

compose.desktop {
    application {
        mainClass = "www.xdyl.hygge.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "XdylUpdate"
            packageVersion = "1.0.0"
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "Xdyl"
                upgradeUuid = "b55e2c6a-7e67-4e5e-a81f-0f2c9c03f55c"
            }
        }
    }
}
