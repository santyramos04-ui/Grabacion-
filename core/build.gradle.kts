plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.santy.grabacion.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
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
    // El núcleo expone las bibliotecas de interfaz comunes a todos los módulos.
    api("androidx.core:core-ktx:1.15.0")
    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.activity:activity-ktx:1.9.3")
    api("androidx.fragment:fragment-ktx:1.8.5")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    api("com.google.android.material:material:1.12.0")
}
