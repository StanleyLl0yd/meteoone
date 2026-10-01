plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":forecast:domain"))
    implementation(project(":backend:gateway"))
    implementation(project(":backend:provider-adapters"))

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
