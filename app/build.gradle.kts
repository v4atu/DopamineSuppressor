import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Credenciales de firma en un archivo local fuera del control de versiones.
// Si no existe, el release se genera sin firmar y el debug sigue funcionando.
val keystoreProperties = Properties().apply {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
        .asText.orNull
    if (text != null) load(text.reader())
}

android {
    namespace = "com.v4atu.dopaminesuppressor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.v4atu.dopaminesuppressor"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)
    testImplementation(libs.junit)
}