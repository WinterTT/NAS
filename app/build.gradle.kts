import com.android.build.api.variant.SigningConfigInfo
import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

val releaseSigningInfo = providers
    .environmentVariablesPrefixedBy("HAVENCAST_RELEASE_")
    .map { environment ->
        val required = listOf(
            "HAVENCAST_RELEASE_STORE_FILE",
            "HAVENCAST_RELEASE_STORE_PASSWORD",
            "HAVENCAST_RELEASE_KEY_ALIAS",
            "HAVENCAST_RELEASE_KEY_PASSWORD",
        )
        val missing = required.filter { environment[it].isNullOrBlank() }
        check(missing.isEmpty()) {
            "Release signing environment variables are missing: ${missing.joinToString()}. " +
                "See docs/09-Release publication guide."
        }

        val storeFile = File(environment.getValue("HAVENCAST_RELEASE_STORE_FILE")).canonicalFile
        check(storeFile.isFile) {
            "HAVENCAST_RELEASE_STORE_FILE must point to an existing PKCS12 keystore."
        }
        val repositoryPath = File(System.getProperty("user.dir")).canonicalFile.toPath()
        check(!storeFile.toPath().startsWith(repositoryPath)) {
            "HAVENCAST_RELEASE_STORE_FILE must be outside the repository."
        }

        SigningConfigInfo(
            storeFile,
            environment.getValue("HAVENCAST_RELEASE_STORE_PASSWORD"),
            environment.getValue("HAVENCAST_RELEASE_KEY_ALIAS"),
            environment.getValue("HAVENCAST_RELEASE_KEY_PASSWORD"),
            "PKCS12",
        )
    }

android {
    namespace = "com.havencast.remote"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.havencast.remote"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.signingConfig.from(releaseSigningInfo)
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
