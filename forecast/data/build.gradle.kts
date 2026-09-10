plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
