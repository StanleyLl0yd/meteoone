plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":backend:provider-gateway"))
    implementation(project(":forecast:openmeteo"))

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
