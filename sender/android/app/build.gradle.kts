import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val deploymentFile = rootProject.file("../../protocol/cambridge-deployment.local.json")
    .takeIf { it.isFile }
    ?: rootProject.file("../../protocol/cambridge-deployment.json")
val deploymentJson = JsonSlurper().parse(deploymentFile) as Map<*, *>
val deploymentComputer = deploymentJson["computer"] as Map<*, *>
val versionManifestFile = rootProject.file("../../VERSION")
val versionManifest = JsonSlurper().parse(versionManifestFile) as Map<*, *>
val versionManifestSchema = 1
require(versionManifest["schemaVersion"] == versionManifestSchema) {
    "VERSION has an unsupported manifest schema"
}
val versionComponents = versionManifest["components"] as? Map<*, *>
    ?: error("VERSION must define component versions")
val releaseVersion = versionComponents["androidSender"] as? String
    ?: error("VERSION must define an Android sender version")
val configuredReleaseVersion = System.getenv("CAMBRIDGE_ANDROID_VERSION")
    ?.takeIf { it.isNotBlank() }
configuredReleaseVersion?.let { configuredVersion ->
    require(configuredVersion == releaseVersion) {
        "CAMBRIDGE_ANDROID_VERSION must match VERSION androidSender"
    }
}
val versionCodeComponentBase = 1_000
val semanticVersionComponentCount = 3
val releaseVersionComponents = releaseVersion.split('.').map { component ->
    component.toIntOrNull() ?: error("VERSION must contain numeric semantic-version components")
}
require(releaseVersionComponents.size == semanticVersionComponentCount) {
    "VERSION must use major.minor.patch format"
}
require(releaseVersionComponents.all { component -> component in 0 until versionCodeComponentBase }) {
    "VERSION components must fit the Android version-code encoding"
}
val releaseVersionCode = releaseVersionComponents.fold(0) { encodedVersion, component ->
    encodedVersion * versionCodeComponentBase + component
}
require(releaseVersionCode > 0) { "Android versionCode must be positive" }

val signingStoreFile = System.getenv("ANDROID_SIGNING_STORE_FILE")
val signingStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
val signingValues = listOf(
    signingStoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword,
)
val releaseSigningConfigured = signingValues.all { !it.isNullOrBlank() }
require(signingValues.all { it.isNullOrBlank() } || releaseSigningConfigured) {
    "Android release signing requires all ANDROID_SIGNING_* values"
}

fun buildConfigString(value: Any?): String {
    val escaped = value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    namespace = "dev.cambridge.sender"
    compileSdk = rootProject.extra["androidCompileSdkVersion"] as Int

    defaultConfig {
        applicationId = "dev.cambridge.sender"
        minSdk = rootProject.extra["androidMinimumSdkVersion"] as Int
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersion

        buildConfigField("String", "CAMBRIDGE_COMPUTER_ID", buildConfigString(deploymentComputer["id"]))
        buildConfigField(
            "String",
            "CAMBRIDGE_COMPUTER_DISPLAY_NAME",
            buildConfigString(deploymentComputer["displayName"]),
        )
        buildConfigField("String", "CAMBRIDGE_COMPUTER_ADDRESS", buildConfigString(deploymentComputer["address"]))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":receiver-discovery"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
