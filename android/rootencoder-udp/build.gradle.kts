plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.mobilewebcam.sender.rootencoder.udp"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(libs.rootencoder.common)
    implementation(libs.core.ktx)
    implementation(libs.rootencoder.srt)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
