# Unified Music — Android music player

Unified Music is a native Android music app built with Kotlin, Jetpack Compose and AndroidX Media3. The account hub integrates **YouTube and Spotify only**. Audius is not part of the new account hub.

## Current app structure

- The account hub is the launcher screen.
- YouTube: Google account authorization with the `youtube.readonly` scope, YouTube Data API search, and a visible embedded YouTube player for playback.
- Spotify: Spotify Authorization Code with PKCE, encrypted token storage, Spotify Web API track search, and Spotify App Remote playback through the official Spotify app.
- The existing local music library, local playlists and Media3 player are still in the project and can be opened from the hub.

## Provider playback boundaries

- YouTube playback remains inside the standard visible YouTube player with its normal controls and ads. The app does not extract audio, hide the player, or provide background playback for YouTube videos.
- Spotify playback is controlled through Spotify App Remote and is performed by the Spotify app. It is not a direct Spotify audio stream inside Media3. Spotify's Android App Remote SDK requires the Spotify app to be installed and the app to be registered in Spotify Developer Dashboard.
- Spotify Development Mode currently requires the developer to have Spotify Premium and limits a client ID to up to five authorized users; Spotify's developer rules can change. See Spotify's current developer access update before configuring access.
- This project is a personal/non-commercial integration. Spotify's platform terms prohibit using its platform to build a commercial streaming integration.

## One-time setup

### Spotify

1. Create an app in [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
2. Select Android for the integration and register package name `com.vedansh.unifiedmusic` with the app's signing fingerprint.
3. Add this exact redirect URI: `unifiedmusic://spotify/callback`.
4. Add the Spotify app's Client ID as the GitHub Actions repository secret `SPOTIFY_CLIENT_ID`. Do not add a client secret; this Android app uses PKCE.
5. Install the Spotify app on the test phone and sign in to the account you want to use.

### YouTube / Google

1. In [Google Cloud Console](https://console.cloud.google.com/), create or select a project and enable **YouTube Data API v3**.
2. Configure the OAuth consent screen and add your Google account as a test user while the app is in testing.
3. Register an Android OAuth client for package `com.vedansh.unifiedmusic` and the signing certificate SHA-1 used by the installed APK.
4. Create an API key for YouTube Data API v3 and add it as the GitHub Actions repository secret `YOUTUBE_API_KEY`.
5. On first connect, tap **Connect Google** in the app and approve the YouTube read-only permission in Google's own authorization UI.

The SHA-1 value must match the build that is installed. For a locally built APK, get it from Android Studio's Gradle signing report. If you use a different release keystore, register that certificate fingerprint as well.

## Build

GitHub Actions downloads the official Spotify App Remote AAR, runs JVM unit tests, builds the debug APK, and uploads it as the `UnifiedMusic-debug-apk` artifact. The build works without the provider secrets, but account connections/search need those credentials configured.

For local builds, first run:

```sh
sh scripts/setup-spotify-sdk.sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

Provide local credentials as Gradle properties or environment variables:

- `SPOTIFY_CLIENT_ID` (or Gradle property `spotifyClientId`)
- `YOUTUBE_API_KEY` (or Gradle property `youtubeApiKey`)

## Privacy and credential handling

- Users authenticate with Google and Spotify directly through the providers' own consent screens. The app never asks for provider passwords.
- Spotify access and refresh tokens are stored in Android encrypted shared preferences.
- Google access tokens are held in memory and requested again through Google Identity when required.
- Never commit API keys, OAuth secrets, refresh tokens or user credentials to the repository.

## Existing local player

The original `MainActivity` remains available for on-device audio, local playlists, liked local tracks, queue controls and Media3 playback. The account hub links to it without moving provider audio into the local player.
