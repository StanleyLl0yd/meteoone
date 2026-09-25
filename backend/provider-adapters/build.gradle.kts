plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":backend:provider-gateway"))
    implementation(project(":backend:server-native"))
    implementation(project(":forecast:openmeteo"))
    implementation(project(":forecast:official"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.commons.compress)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
