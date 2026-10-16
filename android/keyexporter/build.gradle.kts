plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.noop.keyexporter"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.noop.oura.keyexporter"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }
    sourceSets.getByName("main").java.srcDir("../key-transfer/src/main/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
dependencies {
    implementation("androidx.collection:collection:1.4.0")
    implementation("com.google.crypto.tink:tink-android:1.8.0")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

dependencyLocking { lockAllConfigurations() }
