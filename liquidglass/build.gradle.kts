plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.wzhdgithub.liquidglass"
    // Miuix 0.9.3 要求 compileSdk >= 37（见根目录 gradle.properties 说明）
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 对外 API 中出现了 ImageVector 等 Compose 类型，故用 api 暴露
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    api(composeBom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")

    // 液态玻璃的底层能力（backdrop 采样 / 模糊 / 折射 / 高光）：
    // 只用于内部实现，不出现在公开 API 中，故用 implementation
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
}