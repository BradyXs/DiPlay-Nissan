plugins {
    id("com.android.library")
}

val nissanAndroid44Profile = project.findProperty("nissanAndroid44Profile")
    ?.toString()
    ?.equals("true", ignoreCase = true)
    ?: false
android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 19
        multiDexEnabled = true
        buildConfigField("boolean", "NISSAN_ANDROID_44_PROFILE", nissanAndroid44Profile.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all { it.maxHeapSize = "1g" }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":shared"))
    implementation(libs.androidx.activity)
    // Settings use AppCompatResources for vector icons, not AppCompatActivity or widgets.
    implementation(libs.androidx.appcompat.resources)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}
