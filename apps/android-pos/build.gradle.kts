plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tapflow.pos"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "com.tapflow.pos"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("debug") {
            val url = providers.gradleProperty("tapflowApiBaseUrl").orElse("http://10.0.2.2:8080/api/v1").get()
            require(url.matches(Regex("https?://[A-Za-z0-9.:-]+/api/v1"))) { "Invalid tapflowApiBaseUrl" }
            buildConfigField("String", "API_BASE_URL", "\"$url\"")
        }
        getByName("release") {
            val url = providers.gradleProperty("tapflowReleaseApiBaseUrl").orElse("https://configure-tapflow.invalid/api/v1").get()
            require(url.matches(Regex("https://[A-Za-z0-9.:-]+/api/v1"))) { "Release API requires HTTPS" }
            buildConfigField("String", "API_BASE_URL", "\"$url\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":packages:android-auth"))
    implementation(project(":packages:protocol-kotlin"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.coroutines.android)
}
