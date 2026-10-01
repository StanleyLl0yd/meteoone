plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.sl.meteoone.backend.http.MeteoOneServerKt")
}

dependencies {
    implementation(project(":backend:contract"))
    implementation(project(":backend:orchestration"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    runtimeOnly(libs.ktor.server.netty)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
