plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":forecast:domain"))
    api(project(":verification:domain"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
