plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yunpan.tingshu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yunpan.tingshu"
        minSdk = 24
        targetSdk = 34
        versionCode = 25
        versionName = "2.3.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    lint {
        // Media3 UnstableApi：播放引擎已按 SDK 正确选用 HLS/Progressive 源，
        // 此处仅对 @UnstableApi 的 opt-in 提示放行（不改播放逻辑）。
        disable += "UnsafeOptInUsageError"
        abortOnError = true
    }

    testOptions {
        unitTests.all {
            // Windows 本机内存受限：测试进程限 1g、UTF-8，避免 fork worker 启动失败
            it.maxHeapSize = "1g"
            it.jvmArgs("-Dfile.encoding=UTF-8")
            // Robolectric SQLite 用原生实现（默认 LEGACY 的 SQLite 3.22 不支持 ON CONFLICT DO UPDATE，
            // 导致 AnalyticsTest 的 upsert 全挂）
            it.systemProperty("robolectric.sqliteMode", "NATIVE")
        }
    }
}

dependencies {
    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Material3 组件
    implementation("com.google.android.material:material:1.12.0")

    // 布局 / 列表
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")

    // Navigation 单 Activity
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Media3 播放
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 封面加载
    implementation("io.coil-kt:coil:2.6.0")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
