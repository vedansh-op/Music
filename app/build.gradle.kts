plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun buildConfigLiteral(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"" + escaped + "\""
}

val spotifyClientId = providers.environmentVariable("SPOTIFY_CLIENT_ID")
    .orElse(providers.gradleProperty("spotifyClientId"))
    .orElse("")
    .get()
val youtubeApiKey = providers.environmentVariable("YOUTUBE_API_KEY")
    .orElse(providers.gradleProperty("youtubeApiKey"))
    .orElse("")
    .get()

android {
    namespace = "com.vedansh.unifiedmusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vedansh.unifiedmusic"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.5.0"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", buildConfigLiteral(spotifyClientId))
        buildConfigField("String", "YOUTUBE_API_KEY", buildConfigLiteral(youtubeApiKey))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
