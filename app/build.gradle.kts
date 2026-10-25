plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions numera cada compilación; ese número es la versión que compara el actualizador.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.santy.grabacion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.santy.grabacion"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"santyramos04-ui/Grabacion-\"")
    }

    // La clave de firma llega por secretos de GitHub Actions (ver README) y nunca se guarda en el
    // repositorio. Con la misma clave cada APK nuevo se instala encima del anterior.
    val keystorePath = System.getenv("OWL_KEYSTORE_PATH")
    val releaseSigning = if (keystorePath != null && file(keystorePath).exists()) {
        signingConfigs.create("owl") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("OWL_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("OWL_KEY_ALIAS") ?: "owl"
            keyPassword = System.getenv("OWL_KEY_PASSWORD") ?: System.getenv("OWL_KEYSTORE_PASSWORD")
        }
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    val media3Version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
