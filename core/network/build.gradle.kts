plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
