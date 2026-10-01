plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":verification:domain"))
    implementation(project(":backend:provider-gateway"))
    implementation(project(":forecast:openmeteo"))
    implementation(project(":verification:data"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
