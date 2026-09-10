plugins { id("com.android.application") }
android {
    namespace = "com.cruisetune.dbaudit"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "com.cruisetune.dbaudit"; minSdk = 33; targetSdk = 35; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
