plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sl.meteoone.core.preferences"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.androidx.datastore.preferences.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
