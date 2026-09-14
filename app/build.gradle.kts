import com.android.build.api.variant.SigningConfigInfo
import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

val releaseSigningInfo = providers
    .environmentVariablesPrefixedBy("DOMOCAST_RELEASE_")
    .map { environment ->
        val required = listOf(
            "DOMOCAST_RELEASE_STORE_FILE",
            "DOMOCAST_RELEASE_STORE_PASSWORD",
            "DOMOCAST_RELEASE_KEY_ALIAS",
            "DOMOCAST_RELEASE_KEY_PASSWORD",
        )
        val missing = required.filter { environment[it].isNullOrBlank() }
        check(missing.isEmpty()) {
            "Release signing environment variables are missing: ${missing.joinToString()}. " +
                "See docs/09-Release publication guide."
        }

        val storeFile = File(environment.getValue("DOMOCAST_RELEASE_STORE_FILE")).canonicalFile
        check(storeFile.isFile) {
            "DOMOCAST_RELEASE_STORE_FILE must point to an existing PKCS12 keystore."
        }
        val repositoryPath = File(System.getProperty("user.dir")).canonicalFile.toPath()
        check(!storeFile.toPath().startsWith(repositoryPath)) {
            "DOMOCAST_RELEASE_STORE_FILE must be outside the repository."
        }

        SigningConfigInfo(
            storeFile,
            environment.getValue("DOMOCAST_RELEASE_STORE_PASSWORD"),
            environment.getValue("DOMOCAST_RELEASE_KEY_ALIAS"),
            environment.getValue("DOMOCAST_RELEASE_KEY_PASSWORD"),
            "PKCS12",
        )
    }

android {
    namespace = "com.domocast.remote"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.domocast.remote"
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
