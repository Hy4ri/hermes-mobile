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

        // Embed git commit SHA for the About card in Settings
        val gitSha = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    signingConfigs {
        create("release") {
            val isReleaseBuild = gradle.startParameter.taskNames.any { name ->
                name.contains("Release", ignoreCase = true) || name == "build"
            }

            if (isReleaseBuild) {
                val storePath = System.getenv("KEYSTORE_PATH")
                val storePass = System.getenv("KEYSTORE_PASSWORD")
                val alias = System.getenv("KEY_ALIAS")
                val keyPass = System.getenv("KEY_PASSWORD")

                require(!storePath.isNullOrEmpty()) { "KEYSTORE_PATH environment variable is not set" }
                require(!storePass.isNullOrEmpty()) { "KEYSTORE_PASSWORD environment variable is not set" }
                require(!alias.isNullOrEmpty()) { "KEY_ALIAS environment variable is not set" }
                require(!keyPass.isNullOrEmpty()) { "KEY_PASSWORD environment variable is not set" }

                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                // Dummy values for evaluation configuration during non-release builds
                storeFile = file("dummy.keystore")
                storePassword = "dummy"
                keyAlias = "dummy"
                keyPassword = "dummy"
            }
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
        pickFirsts += "META-INF/AL2.0"
        pickFirsts += "META-INF/LGPL2.1"
        pickFirsts += "META-INF/LICENSE"
        pickFirsts += "META-INF/LICENSE.md"
        pickFirsts += "META-INF/LICENSE-notice.md"
        pickFirsts += "META-INF/NOTICE"
        pickFirsts += "META-INF/NOTICE.md"
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
  debugImplementation(libs.leakcanary.android)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Networking
  implementation(libs.okhttp)
  debugImplementation(libs.okhttp.logging)
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
  androidTestImplementation(libs.mockk.android)

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
