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
        // Backend address and per-install key (WS03-08). Set in ~/.gradle/gradle.properties or with -P,
        // never in Git. Defaults reach the mock backend from the Android emulator.
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
        // Contract fixtures live at the repo root; tests read them in place.
        unitTests.all { it.systemProperty("avn.repoRoot", rootDir.parentFile.absolutePath) }
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
    implementation(libs.okhttp)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.hilt.testing)
    kspTest(libs.hilt.compiler)
}
