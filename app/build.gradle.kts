import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use(::load)
    }
}

val gstreamerSdkDir = (
    localProperties.getProperty("gstreamer.sdk.dir")
        ?: System.getenv("GSTREAMER_ANDROID_ROOT")
        ?: System.getenv("GSTREAMER_ROOT_ANDROID")
    )?.takeIf { it.isNotBlank() }
    ?.replace('\\', '/')
    ?: error("Set gstreamer.sdk.dir in local.properties or GSTREAMER_ANDROID_ROOT before building XIRO Lite.")

val gstreamerGeneratedJavaDir =
    layout.buildDirectory.dir("generated/source/gstreamer/java").get().asFile.absolutePath.replace('\\', '/')
val gstreamerGeneratedAssetsDir =
    layout.buildDirectory.dir("generated/assets/gstreamer").get().asFile.absolutePath.replace('\\', '/')
val ffmpegSdkDir = (
    localProperties.getProperty("ffmpeg.sdk.dir")
        ?: System.getenv("FFMPEG_ANDROID_ROOT")
        ?: System.getenv("FFMPEG_ROOT_ANDROID")
    )?.takeIf { it.isNotBlank() }
    ?.replace('\\', '/')
    ?: error("Set ffmpeg.sdk.dir in local.properties or FFMPEG_ANDROID_ROOT before building XIRO Lite.")
val ffmpegGeneratedJniLibsDir =
    layout.buildDirectory.dir("generated/jniLibs/ffmpeg").get().asFile.absolutePath.replace('\\', '/')

val syncFfmpegJniLibs by tasks.registering(Sync::class) {
    from(file(ffmpegSdkDir)) {
        include("arm64-v8a/lib/*.so")
        include("armeabi-v7a/lib/*.so")
        eachFile {
            val abi = relativePath.segments.firstOrNull() ?: return@eachFile
            path = "$abi/$name"
        }
        includeEmptyDirs = false
    }
    into(ffmpegGeneratedJniLibsDir)
}

tasks.named("preBuild") {
    dependsOn(syncFfmpegJniLibs)
}

android {
    namespace = "com.example.xirolite"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.example.xirolite"
        minSdk = 24
        targetSdk = 36
        versionCode = 186
        versionName = "0.4.86-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGSTREAMER_UNIVERSAL_ROOT=$gstreamerSdkDir",
                    "-DGSTREAMER_JAVA_SRC_DIR=$gstreamerGeneratedJavaDir",
                    "-DGSTREAMER_ASSETS_DIR=$gstreamerGeneratedAssetsDir",
                    "-DFFMPEG_ANDROID_ROOT=$ffmpegSdkDir"
                )
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }

        create("sideload") {
            initWith(getByName("debug"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    sourceSets {
        getByName("main") {
            assets.srcDir(gstreamerGeneratedAssetsDir)
            jniLibs.srcDir(ffmpegGeneratedJniLibsDir)
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation(project(":media3-exoplayer-rtsp-xiro"))
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("org.mapsforge:mapsforge-map-android:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
