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
        versionCode = 85
        versionName = "1.85"

        ndk {
            // 只打真机 ABI：x86/x86_64 仅模拟器用，armeabi(ARMv5) 早已淘汰。
            // VLC 的 so 很大，砍掉这三个能省下将近一半体积。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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

    packaging {
        jniLibs {
            // VLC 的 so 未压缩时有 90MB+（arm64 52MB / armv7 39MB），必须压缩打包：
            // 压缩后约 42MB，升级包才控制得住；安装时由系统解压，运行性能不受影响。
            useLegacyPackaging = true
            // VLC 自带 libc++_shared.so；将来若再引入别的 native 库也可能带同名文件，
            // 出现重复时保留一份，避免 "More than one file was found" 直接构建失败。
            pickFirsts += "**/libc++_shared.so"
        }
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

    // VLC 内核：仅在设备缺少 MP2/AC-3/E-AC-3 等音频解码器时兜底（正常播放全程走 ExoPlayer）。
    // 换掉原来的 IJK，原因是它预编译的 FFmpeg 只含 aac/flac/mp3，连 MP2、AC-3 都没有，
    // 「有画面没声音」正是它造成的。VLC 的 FFmpeg 是完整构建，mp2/AC-3/E-AC-3/DTS 全覆盖。
    // 版本锁 3.6.5：3.7.x 的 aar 声明 minCompileSdk=36，而本项目 compileSdk 35 / AGP 8.5.2，
    // 升级会直接构建失败；3.6.5 的 minCompileSdk=1，API 与 3.7.x 完全一致。
    implementation("org.videolan.android:libvlc-all:3.6.5")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 局域网配对 HTTP 服务
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // 图片加载（台标 / 二维码）
    implementation("io.coil-kt:coil:2.6.0")

    // 二维码生成（局域网配对页 / 手机填写页展示的二维码）
    implementation("com.google.zxing:core:3.5.3")
    // 相机扫码：CameraX 取景 + MLKit 识别。
    // 不用 zxing 自带的 CaptureActivity：它只能对「取景框裁剪出来的那小块」解码，
    // 框一小就必须把码精确怼进框里，反而更难扫。MLKit 是对整帧识别，
    // 取景框只当视觉引导，框外一点也能认出来，弱光/反光/斜着拿都明显更稳。
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Java 8+ API 脱糖（老系统兼容）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
