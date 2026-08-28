import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
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

// ============================================================
// 双版本发布：
//   packageMsi          → 完整版 MSI（jpackage 捆绑 JRE，开箱即用，体积大）
//   packageThinZip      → 轻量版 ZIP（不含 JRE，仅几 MB，需用户自装 Java 17+）
// ============================================================

val thinDistDir = layout.buildDirectory.dir("compose/binaries/main-thin")

val prepareThinJar = tasks.register<Copy>("prepareThinJar") {
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app/NebulaUpdater"))
    into(thinDistDir.map { it.dir("NebulaUpdater") })
}

val packageThinZip = tasks.register<Zip>("packageThinZip") {
    group = "compose desktop"
    description = "轻量版：应用本体 zip，不捆绑 Java 运行时"
    dependsOn(prepareThinJar)
    archiveBaseName.set("NebulaUpdater-thin")
    archiveExtension.set("zip")
    from(thinDistDir.map { it.dir("NebulaUpdater") })
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-thin"))
}