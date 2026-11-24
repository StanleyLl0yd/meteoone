plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sl.meteoone.core.location"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

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
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.kotlin.test.junit)
}
