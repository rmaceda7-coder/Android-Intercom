plugins {
    id("com.android.application")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "com.ramiromaceda.intercom"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.ramiromaceda.intercom"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
