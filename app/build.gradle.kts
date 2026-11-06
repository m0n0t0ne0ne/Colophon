plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.m0n0t0ne0ne.colophon"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.m0n0t0ne0ne.colophon"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.8.1"
        ndk { abiFilters += ((project.findProperty("colophon.abis") as String?) ?: "arm64-v8a").split(",") }
    }

    signingConfigs {
        val path = System.getenv("COLOPHON_KEYSTORE")
        if (path != null) {
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("COLOPHON_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("COLOPHON_KEY_ALIAS")
                keyPassword = System.getenv("COLOPHON_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += "zip" }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                (project.findProperty("robolectric.mirror") as String?)?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
            }
        }
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val compose = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.alphacephei:vosk-android:0.3.75") { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.0")
    testImplementation(compose)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
