import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.footprints.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.footprints.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 22
        versionName = "4.6"
        manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY", "")
    }

    // 签名密码从 local.properties 读取（不入库）；缺失时回退 debug 签名，
    // 保证公开仓库的克隆者也能构建，开发者本机行为不变（debug/release 共用
    // 同一签名是为了高德 Key 的 SHA1 绑定在两种构建下都生效）
    val storePwd = localProps.getProperty("SIGNING_STORE_PASSWORD", "")
    val keyPwd = localProps.getProperty("SIGNING_KEY_PASSWORD", "")
    val hasReleaseSigning = storePwd.isNotEmpty() &&
            rootProject.file("signing/footprints.jks").exists()

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/footprints.jks")
            storePassword = storePwd
            keyAlias = "footprints"
            keyPassword = keyPwd
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 3dmap 10.x 已内置定位 SDK（com.amap.api.location.*），无需单独引入 location；
    // 逆地理编码用定位 SDK 的 setNeedAddress(true)，勿引 search SDK（与 3dmap 类冲突）
    implementation("com.amap.api:3dmap:10.0.600")
}
