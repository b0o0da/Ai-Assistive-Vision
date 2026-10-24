plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "avn.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "avn.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        val realSeams = providers.gradleProperty("avn.realSeams").getOrElse("")
        buildConfigField("String", "REAL_SEAMS", "\"$realSeams\"")
        // Dev default reaches the backend's mock mode from the emulator; override per README with
        // -Pavn.backendUrl=... and -Pavn.apiKey=... (must match an APP_API_KEYS entry, never commit a real one).
        val backendUrl = providers.gradleProperty("avn.backendUrl").getOrElse("http://10.0.2.2:8000")
        val apiKey = providers.gradleProperty("avn.apiKey").getOrElse("dev-install-key")
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "API_KEY", "\"$apiKey\"")
        manifestPlaceholders["cleartext"] = "false"
    }
    buildTypes {
        debug {
            // Lets a debug build reach the mock backend on a LAN address over http.
            manifestPlaceholders["cleartext"] = "true"
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":engine"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.okhttp)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.testing)
    kspTest(libs.hilt.compiler)
}

// Route-parsing fixtures live at the repo root (contracts/fixtures/api); tests read them in place,
// same convention as :contracts' own FixtureTest. The Android plugin doesn't expose a single
// "test" task (it's testDebugUnitTest / testReleaseUnitTest per variant), so target every Test task.
tasks.withType<Test>().configureEach {
    systemProperty("avn.repoRoot", rootDir.parentFile.absolutePath)
}
