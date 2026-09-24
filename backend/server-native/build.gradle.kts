plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":forecast:official"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

tasks.test {
    providers.systemProperty("meteoone.server.eccodes.bundle").orNull?.let { bundle ->
        systemProperty("meteoone.server.eccodes.bundle", bundle)
    }
}
