plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":contracts"))
    testImplementation(libs.junit)
}

// Lets tests read the shared schemas in contracts/schemas at the repo root.
tasks.test {
    systemProperty("avn.repoRoot", rootDir.parentFile.absolutePath)
}
