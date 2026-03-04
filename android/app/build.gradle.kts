plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.security.gsmrelay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.security.gsmrelay"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    flavorDimensions += "mode"
    productFlavors {
        create("gateway") {
            dimension = "mode"
            buildConfigField("boolean", "IS_GATEWAY", "true")
            versionNameSuffix = "-gateway"
        }
        create("client") {
            dimension = "mode"
            applicationIdSuffix = ".client"
            buildConfigField("boolean", "IS_GATEWAY", "false")
            versionNameSuffix = "-client"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.2"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // For CSV
    implementation("com.opencsv:opencsv:5.7.1")

    // For JSON and data
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // For Room Database (salvare date locale)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.room:room-ktx:2.6.1")


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val projectRootDir = rootProject.projectDir.parentFile

val copyGatewayDebugApkToRoot by tasks.registering {
    doNotTrackState("Copies artifact into repo root that contains tool-managed lock files.")
    doLast {
        copy {
            from(layout.buildDirectory.file("outputs/apk/gateway/debug/app-gateway-debug.apk"))
            into(projectRootDir)
            rename { "GSMRelayGateway-debug.apk" }
        }
    }
}

val copyClientDebugApkToRoot by tasks.registering {
    doNotTrackState("Copies artifact into repo root that contains tool-managed lock files.")
    doLast {
        copy {
            from(layout.buildDirectory.file("outputs/apk/client/debug/app-client-debug.apk"))
            into(projectRootDir)
            rename { "GSMRelayClient-debug.apk" }
        }
    }
}

tasks.matching { it.name == "assembleGatewayDebug" }.configureEach {
    finalizedBy(copyGatewayDebugApkToRoot)
}

tasks.matching { it.name == "assembleClientDebug" }.configureEach {
    finalizedBy(copyClientDebugApkToRoot)
}
