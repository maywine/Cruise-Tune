import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName = appVersion.getProperty("VERSION_NAME")
    ?: error("VERSION_NAME is required in version.properties")
val appVersionCode = appVersion.getProperty("VERSION_CODE")?.toIntOrNull()
    ?: error("VERSION_CODE must be an integer in version.properties")
require(appVersionCode in 1..2100000000) { "VERSION_CODE is outside the Android range" }

val releaseSigning = listOf("CRUISE_KEYSTORE_PATH", "CRUISE_STORE_PASSWORD", "CRUISE_KEY_ALIAS", "CRUISE_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigning.values.any { !it.isNullOrEmpty() }
if (hasReleaseSigning) require(releaseSigning.values.all { !it.isNullOrEmpty() }) {
    "All CRUISE release signing environment variables must be supplied together"
}

android {
    namespace = "com.cruisetune.player"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.cruisetune.player"
        minSdk = 23
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "Cruise Tune"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (hasReleaseSigning) create("ciRelease") {
            storeFile = file(releaseSigning.getValue("CRUISE_KEYSTORE_PATH")!!)
            storePassword = releaseSigning.getValue("CRUISE_STORE_PASSWORD")
            keyAlias = releaseSigning.getValue("CRUISE_KEY_ALIAS")
            keyPassword = releaseSigning.getValue("CRUISE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("ciRelease")
        }
        create("authCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".authcheck"
            versionNameSuffix = "-authcheck"
            manifestPlaceholders["appLabel"] = "Cruise Tune 授权验证"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = providers.gradleProperty("deviceTestBuildType").getOrElse("debug")
    buildFeatures { aidl = true }
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/player-fixtures"))
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    maxHeapSize = "1536m"
    maxParallelForks = 1
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    val nativeFlac = providers.gradleProperty("nativeFlacLibraryDir")
    if (nativeFlac.isPresent) {
        jvmArgs("-Djava.library.path=${file(nativeFlac.get()).absolutePath}")
        inputs.file(file(nativeFlac.get()).resolve("libflacJNI.so"))
        filter { includeTestsMatching("com.cruisetune.player.playback.SoftwareFlacPlaybackTest") }
        providers.gradleProperty("flacSamplesDir").orNull?.let {
            systemProperty("flacSamplesDir", it)
            inputs.files(fileTree(it) { include("*.flac", "*.FLAC") })
        }
    } else {
        // Native decoding has its own host run; ordinary Robolectric uses platform shadows.
        exclude("**/SoftwareFlacPlaybackTest*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    val media3 = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // DefaultExtractorsFactory discovers this module and decodes FLAC to PCM before rendering.
    implementation(project(":decoder-flac"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.15.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
