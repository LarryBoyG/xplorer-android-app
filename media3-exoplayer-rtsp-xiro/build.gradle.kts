plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.exoplayer.rtsp"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.26.1")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
