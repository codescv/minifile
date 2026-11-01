import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.minifile.explorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.minifile.explorer"
        minSdk = 26
        targetSdk = 35
        versionCode = 10015
        versionName = "1.0.15"

        buildConfigField("boolean", "THUMBNAILS", "true")
        val apiBase = System.getenv("MINIFILE_UPDATE_API_BASE")
            ?.takeIf { it.isNotBlank() }
            ?: "https://api.github.com/repos/codescv/minifile"
        buildConfigField("String", "UPDATE_API_BASE", "\"$apiBase\"")
        val gdriveClientId = (System.getenv("MINIFILE_GDRIVE_CLIENT_ID") ?: "")
            .trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "GDRIVE_DEFAULT_CLIENT_ID", "\"$gdriveClientId\"")
    }

    val keystorePath = System.getenv("MINIFILE_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).isFile }
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MINIFILE_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("MINIFILE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("MINIFILE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-DEBUG"
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(kotlin("stdlib-jdk7"))
    implementation(kotlin("stdlib-jdk8"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
