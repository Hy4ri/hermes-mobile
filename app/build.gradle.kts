import org.gradle.api.tasks.testing.Test

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.room)
  alias(libs.plugins.ksp)
}

android {
    namespace = "com.m57.hermescontrol"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.m57.hermescontrol"
        minSdk = 26
        targetSdk = 36
        // Version overrides passed from CI via -PversionName / -PversionCode
        // Falls back to defaults for local development.
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as? String) ?: "1.0-dev"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "hermes-control-release.keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "hermes_release"
            keyAlias = System.getenv("KEY_ALIAS") ?: "hermes-control-release"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "hermes_release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs["release"]
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Networking
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging)
  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.gson)
  implementation(libs.gson)

  // Encrypted storage
  implementation(libs.androidx.security.crypto)
  implementation("androidx.startup:startup-runtime:1.1.1")

  // Local database (Room) — message persistence
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // Background notifications (WorkManager)
  implementation(libs.androidx.work.runtime)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.vintage.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockk)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

tasks.register<Exec>("ktlintCheck") {
    group = "verification"
    description = "Runs ktlint check."
    workingDir = rootProject.projectDir
    commandLine("sh", "-c", "ktlint \"app/src/**/*.kt\"")
}
tasks.register<Exec>("ktlintFormat") {
    group = "formatting"
    description = "Runs ktlint format."
    workingDir = rootProject.projectDir
    commandLine("sh", "-c", "ktlint -F \"app/src/**/*.kt\"")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
