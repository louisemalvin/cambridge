plugins {
    alias(libs.plugins.android.library)
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
    kotlin {
        jvmToolchain(17)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api(libs.rootencoder.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.rootencoder.srt)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
