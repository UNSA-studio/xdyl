plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "www.xdyl.hygge.com"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("$projectDir/xdyl_keystore.jks")
            storePassword = "xdyl123"
            keyAlias = "xdyl"
            keyPassword = "xdyl123"
        }
    }

    defaultConfig {
        applicationId = "www.xdyl.hygge.com"
        minSdk = 28
        // 保持 28 以豁免 Android10+ W^X 限制：targetSdk>=29 的应用私有目录 noexec，
        // 无法执行下载解压的 Python ELF（Termux 同款做法）
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        // GIF 库自带 native 解码，仅保留常用 ABI 控制体积
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            // CI 分发的就是这个 debug 包：开启 R8 + 资源压缩以显著减小体积
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // ECH 原生层（BoringSSL，由 CI 编译并放进 jniLibs）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 只保留中英文资源（去掉库里其它语言的 strings）
    defaultConfig {
        resourceConfigurations += listOf("zh", "en")
    }

    // 剔除无用的元数据文件
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.mockito:mockito-core:4.11.0")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.code.gson:gson:2.10.1")   // 新增
    implementation("dev.mobile:dadb:2.0.0")            // ADB 客户端库
    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.28") // GIF 动图（自带 native 解码，避开系统 AnimatedImageDrawable 崩溃）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // 7z 解压（纯 Java：commons-compress + xz）
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
