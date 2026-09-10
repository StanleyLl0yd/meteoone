plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sl.meteoone.core.location"
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
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}
