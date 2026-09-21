plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sl.meteoone.forecast.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
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

    implementation(project(":forecast:domain"))
    api(project(":verification:domain"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commons.compress)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
