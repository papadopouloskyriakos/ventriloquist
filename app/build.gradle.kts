import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing comes from Gradle properties outside the repository, normally
// ~/.gradle/gradle.properties on the build machine:
//   ventriloquist.release.storeFile=/path/to/release.jks
//   ventriloquist.release.storePassword=...
//   ventriloquist.release.keyAlias=...
//   ventriloquist.release.keyPassword=...
// Without them the release build is produced unsigned.
val releaseStoreFile = providers.gradleProperty("ventriloquist.release.storeFile").orNull
val releaseSigning = releaseStoreFile != null && file(releaseStoreFile).isFile

android {
    namespace = "net.nuclearlighters.ventriloquist"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.nuclearlighters.ventriloquist"
        minSdk = 30
        targetSdk = 34
        versionCode = 11
        versionName = "0.4.5"
    }

    signingConfigs {
        if (releaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = providers.gradleProperty("ventriloquist.release.storePassword").get()
                keyAlias = providers.gradleProperty("ventriloquist.release.keyAlias").get()
                keyPassword = providers.gradleProperty("ventriloquist.release.keyPassword").get()
            }
        }
    }

    buildTypes {
        debug {
            // A debug build installs beside the release build instead of clashing with its signature.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            if (releaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
