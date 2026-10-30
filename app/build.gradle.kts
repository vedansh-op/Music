import java.util.Base64

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

// GitHub-hosted runners are clean machines. The default Android debug keystore is
// regenerated on every run, which changes the OAuth SHA-1 and can break Google login.
// Supplying all four secrets below gives CI one stable signing identity.
val ciSigningStoreBase64 = providers.environmentVariable("ANDROID_SIGNING_KEYSTORE_BASE64").orElse("").get()
val ciSigningStorePassword = providers.environmentVariable("ANDROID_SIGNING_KEYSTORE_PASSWORD").orElse("").get()
val ciSigningKeyAlias = providers.environmentVariable("ANDROID_SIGNING_KEY_ALIAS").orElse("").get()
val ciSigningKeyPassword = providers.environmentVariable("ANDROID_SIGNING_KEY_PASSWORD").orElse("").get()
val ciSigningValues = listOf(
    ciSigningStoreBase64,
    ciSigningStorePassword,
    ciSigningKeyAlias,
    ciSigningKeyPassword
)
val ciSigningEnabled = ciSigningValues.any { it.isNotBlank() }

if (ciSigningEnabled && ciSigningValues.any { it.isBlank() }) {
    error("Stable CI signing is partially configured. Set all four ANDROID_SIGNING_* GitHub Actions secrets or remove them all.")
}

val ciSigningStoreFile = layout.buildDirectory.file("ci-signing/unifiedmusic.jks").get().asFile
if (ciSigningEnabled) {
    ciSigningStoreFile.parentFile.mkdirs()
    ciSigningStoreFile.writeBytes(Base64.getMimeDecoder().decode(ciSigningStoreBase64))
}

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

    signingConfigs {
        if (ciSigningEnabled) {
            create("ciDebug") {
                storeFile = ciSigningStoreFile
                storePassword = ciSigningStorePassword
                keyAlias = ciSigningKeyAlias
                keyPassword = ciSigningKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (ciSigningEnabled) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
