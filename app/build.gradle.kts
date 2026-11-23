plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iptv.player"
    compileSdk = 35

    // 发布签名：CI 通过环境变量注入（优先 GitHub Secrets，未配置时用仓库内固定的 CI 密钥）。
    // 本地直接 assembleDebug 不受影响；未注入密钥时 release 走默认未签名。
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank()) {
                storeType = "PKCS12"
                storeFile = rootProject.file("app/$ksPath")
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.iptv.player"
        // minSdk 21：覆盖 Android 5.0 起的电视盒子/老电视（Media3、Coil、AndroidX 的最低要求）
        minSdk = 21
        targetSdk = 35
        versionCode = 49
        versionName = "1.49"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 仅当 CI 注入了密钥时才应用正式签名配置
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 核心库脱糖：让 Android 5.x/6.x 也能用 Java 8+ 的日期与集合 API
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // 生命周期 / 协程
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media3 ExoPlayer（含 HLS / LL-HLS）
    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 局域网配对 HTTP 服务
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // 图片加载（台标 / 二维码）
    implementation("io.coil-kt:coil:2.6.0")

    // 二维码生成
    implementation("com.google.zxing:core:3.5.3")

    // Java 8+ API 脱糖（老系统兼容）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
