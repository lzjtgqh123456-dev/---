plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.liuxue.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.liuxue.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.9.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 只为 arm64 打包原生推理库（其它 ABI 无此库，加载时优雅降级）
        ndk { abiFilters.add("arm64-v8a") }
        // 只为 arm64 打包原生推理库（其它 ABI 无此库，加载时会优雅降级）
        resourceConfigurations += listOf("zh", "en", "ru")
    }

    signingConfigs {
        create("release") {
            // 用工具链目录里的 keystore；正式发布请替换为你自己的并妥善保管
            storeFile = rootProject.file("keystore/release.keystore")
            storePassword = "testpass123"
            keyAlias = "testkey"
            keyPassword = "testpass123"
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // 只为 arm64 打包原生推理库（其它 ABI 没有该库，加载时会优雅降级）
    defaultConfig {
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // ONNX Runtime 的 AAR 自带 4 个 ABI，只留 arm64（其它 ABI 本工程没有原生库）
        jniLibs.excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**")
    }
    // 词典数据库已被 SQLite 压缩过，再压缩意义不大且拖慢首次启动
    androidResources { noCompress += "db" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // 已随 Coil 打进包里，显式声明以稳定依赖
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 离线 OCR：ONNX Runtime + PP-OCR 模型（模型首次使用时下载，不打进 APK）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // 免 Key 搜索：解析必应/百度搜索结果页的 HTML（很小，约 400KB）
    implementation("org.jsoup:jsoup:1.18.3")

    // PDF 附件解析（AI 聊天里读 PDF 文字；AAR 约 3.2MB）
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // 相册图片的 EXIF 方向纠正（OCR 前把图片转正）
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    debugImplementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")

    // 真机仪器化测试：词典引擎、加密存储
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    }
}
