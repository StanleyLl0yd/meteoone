plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sl.meteoone.forecast.repository"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    implementation(project(":core:database"))
    implementation(project(":forecast:data"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
