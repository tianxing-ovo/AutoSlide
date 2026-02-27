import com.android.build.gradle.internal.api.BaseVariantOutputImpl

// AGP
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    // 命名空间
    namespace = "com.ltx"
    // 编译时使用的Android SDK版本
    compileSdk = 36
    defaultConfig {
        // 应用ID: 包名
        applicationId = "com.ltx"
        // 最低支持SDK版本
        minSdk = 34
        // 目标设备的SDK版本
        targetSdk = 36
        // 版本号
        versionCode = 4
        // 版本名称
        versionName = "1.3"
        // 单元测试
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    // 自定义APK输出名称
    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "AutoSlide-v${defaultConfig.versionName}.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        // 启用视图绑定
        viewBinding = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
