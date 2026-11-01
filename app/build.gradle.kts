plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tesaauto.mirror"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tesaauto.mirror"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Ktor CIO Server & WebSockets
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cors)

    // Shizuku API
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
