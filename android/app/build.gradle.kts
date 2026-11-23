import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Репозиторий и ключ шифрования создаёт MThread Store Admin в ~/Library/Application Support/MThread Store.
val storeProps = Properties().apply {
    listOf(
        File(System.getProperty("user.home"), "Library/Application Support/MThread Store/store.properties"),
        rootProject.file("../store.properties"),
    ).firstOrNull { it.exists() }?.reader()?.use { load(it) }
}

android {
    namespace = "app.mthread.store"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mthread.store"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
        buildConfigField("String", "STORE_REPO", "\"${storeProps.getProperty("repo", "")}\"")
        buildConfigField("String", "STORE_KEY", "\"${storeProps.getProperty("key", "")}\"")
    }
    buildTypes {
        release {
            // R8 выключен: Cladroid на том же стеке без R8 работает, а сборка Store с R8 падала при запуске.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подпись тем же ключом, что и остальные ваши APK: иначе Store не сможет обновлять сам себя.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
