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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // La app solo une los módulos; cada uno trae sus propias bibliotecas.
    implementation(project(":core"))
    implementation(project(":recorder"))
    implementation(project(":vault"))
    implementation(project(":collection"))
}
