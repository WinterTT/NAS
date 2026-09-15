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

/**
 * 发布闸门：AdMob 官方测试 ID 不能进 Release。
 * 测试 ID 出不了广告收入，而且商店审核可能判定为无效流量。
 * 把 src/main/res/values/ad_config.xml 换成自己的 App ID / 广告位 ID 后，这个检查自然通过。
 */
val checkAdMobTestIds by tasks.registering {
    description = "校验 Release 没有使用 AdMob 官方测试 ID"
    val adConfigFile = layout.projectDirectory.file("src/main/res/values/ad_config.xml").asFile
    inputs.file(adConfigFile)
    doLast {
        val text = adConfigFile.readText()
        check(!text.contains("3940256099942544")) {
            "ad_config.xml 里还是 AdMob 官方测试 ID，不能用于 Release。请先换成自己账号下的 App ID 与广告位 ID（见 docs/11-广告接入.md）。"
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkAdMobTestIds)
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

    // 广告：AdMob SDK + 同意流程（UMP）
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
