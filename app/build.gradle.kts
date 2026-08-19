import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.cedar17.zjuconnect"
    compileSdk = 36
    compileSdkMinor = 1
    compileSdkExtension = 20
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.cedar17.zjuconnect"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(files("libs/zju-connect-core.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val releaseVersionName = android.defaultConfig.versionName ?: "unknown"

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variantBuilder ->
        variantBuilder.enable = false
    }

    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }

    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("zju-connect-v$releaseVersionName-arm64-v8a.apk")
        }
    }
}

val goCoreAar = layout.projectDirectory.file("libs/zju-connect-core.aar")

tasks.register("verifyGoCoreAar") {
    group = "verification"
    description = "Checks that the reproducibly built Go core AAR is available."
    inputs.file(goCoreAar)
    doLast {
        check(goCoreAar.asFile.isFile) {
            "Missing ${goCoreAar.asFile}. Run scripts\\bootstrap-gomobile-toolchain.ps1 " +
                "then scripts\\build-gomobile-aar.ps1 before building Android."
        }
    }
}

tasks.named("preBuild") {
    dependsOn("verifyGoCoreAar")
}
