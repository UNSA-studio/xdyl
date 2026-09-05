/*
 * fclcore: 从 Fold Craft Launcher (https://github.com/FCL-Team/FoldCraftLauncher)
 * vendor 而来的整合包安装核心（最小依赖闭包 282 个文件）。
 *
 * 原始代码版权 (C) 2024 FCL-Team & contributors，遵循 GNU General Public License v3.0。
 * 本模块及其使用方随本项目整体以 GPL-3.0 分发。
 * 包名保持 com.tungsten.fclcore 原样，便于后续跟随上游同步。
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

android {
    namespace = "com.tungsten.fclcore"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // fclcore 使用 java.time / java.util.stream / Optional 等需要脱糖
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("commons-io:commons-io:2.15.1")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("org.glavo:chardet:2.5.0")
    implementation("org.jenkins-ci:constant-pool-scanner:1.2")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
