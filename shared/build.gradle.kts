import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // iOS targets
    listOf(
        iosX64(),      // For iOS simulator on Intel Macs
        iosArm64(),    // For actual iOS devices
        iosSimulatorArm64() // For iOS simulator on Apple Silicon Macs
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true

            // Export dependencies to make them available in iOS
            export("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Shared dependencies available on all platforms
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            }
        }

        val androidMain by getting {
            dependencies {
                // Android-specific dependencies
                implementation("androidx.core:core-ktx:1.12.0")
                implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
            }
        }

        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                // iOS-specific dependencies if needed
                // implementation("some.ios.specific:library")
            }
        }

        val iosX64Main by getting {
            dependsOn(iosMain)
        }

        val iosArm64Main by getting {
            dependsOn(iosMain)
        }

        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        // iOS test sources if needed
        val iosTest by creating {
            dependsOn(commonTest)
        }
    }
}

android {
    namespace = "com.example.blekmp.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    // Optional: Add packaging options if needed
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}