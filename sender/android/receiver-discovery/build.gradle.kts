plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.cambridge.discovery"
    compileSdk = rootProject.extra["androidCompileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["androidMinimumSdkVersion"] as Int
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
