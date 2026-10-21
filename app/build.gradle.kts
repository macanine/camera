import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val version = appVersion.getProperty("versionName")
val match = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-rc\\.([1-9][0-9]*))?").matchEntire(version)
    ?: error("Invalid versionName in version.properties")
val (major, minor, patch) = match.groupValues.drop(1).take(3).map(String::toInt)
val rc = match.groupValues[4].takeIf(String::isNotEmpty)?.toInt()
require(major in 0..2099 && minor in 0..99 && patch in 0..99 && (rc == null || rc in 1..98))
val androidVersionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + (rc ?: 99)
val signing = Properties().apply {
    rootProject.file(".signing/signing.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(name: String): String? = System.getenv(name) ?: signing.getProperty(name)
val signingNames = listOf("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
val hasSigning = signingNames.all { !signingValue(it).isNullOrBlank() }
if (System.getenv("CI") == "true" && gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) } &&
    System.getenv("REQUIRE_RELEASE_SIGNING") == "true") require(hasSigning) { "Release signing credentials are required" }

dependencyLocking { lockAllConfigurations() }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

android {
    namespace = "com.nic.photoanswer"
    compileSdk = 36
    testBuildType = providers.gradleProperty("deviceTestBuildType").orElse("debug").get().also {
        require(it in setOf("debug", "release"))
    }

    defaultConfig {
        applicationId = "com.nic.photoanswer"
        minSdk = 26
        targetSdk = 36
        versionCode = androidVersionCode
        versionName = version
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"macanine/camera\"")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasSigning) create("distribution") {
            storeFile = rootProject.file(signingValue("ANDROID_KEYSTORE_PATH")!!)
            storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = signingValue("ANDROID_KEY_ALIAS")
            keyPassword = signingValue("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasSigning) signingConfig = signingConfigs.getByName("distribution")
            else if (providers.gradleProperty("smokeRelease").orNull == "true" &&
                System.getenv("REQUIRE_RELEASE_SIGNING") != "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            testProguardFiles("proguard-test-rules.pro")
            if (testBuildType == "release") proguardFile("proguard-instrumentation-target.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraX = "1.5.3"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")


    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Native, offline Markdown + math; no WebView or remote rendering service.
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:inline-parser:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16")
}
