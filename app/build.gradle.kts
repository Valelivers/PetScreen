plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.blibla.animeshimejipetscreen"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blibla.animeshimejipetscreen"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API base dari backend kamu (sesuaikan domain)
        buildConfigField("String", "BASE_URL", "\"https://wallserver.xyz/blibla/shimeji/\"")
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

    // Disarankan pakai Java 17 untuk Compose + stack modern
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // ===== Existing template dependencies =====
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ===== New: Navigation (Bottom Nav + screens) =====
    implementation(libs.androidx.navigation.compose)

    // ===== New: Network (Retrofit + Moshi + OkHttp) =====
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.browser)
    implementation(libs.play.services.ads.api)
    implementation(libs.play.services.base)
    implementation(libs.androidx.lifecycle.process)
    debugImplementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    // ===== New: Room (cache DB) =====
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ===== New: WorkManager (sync worker) =====
    implementation(libs.androidx.work.runtime.ktx)

    // ===== New: DataStore (remember last shimeji) =====
    implementation(libs.androidx.datastore.preferences)

    // ===== New: Coil (load icon) =====
    implementation(libs.coil.compose)

    // ===== Tests =====
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.cardview:cardview:1.0.0")
}
