# Unified Music — Android music player

Unified Music is a native Android music app built with Kotlin, Jetpack Compose and AndroidX Media3. The account hub integrates **YouTube and Spotify**.

## Current app structure

- The account hub is the launcher screen.
- YouTube: Google account authorization with an explicit account chooser, live token validation against the signed-in channel, YouTube Data API music search with pagination, signed-in playlist browsing, liked-video library access, and a visible YouTube player for playback. OAuth failures surface their Google status code and the likely configuration fix instead of silently leaving a stale "connected" flag.
- Spotify: Spotify Authorization Code with PKCE, encrypted token storage, Spotify Web API track search, and Spotify App Remote playback through the official Spotify app.
- The existing local music library, local playlists and Media3 player are still in the project and can be opened from the hub.

## Guest mode and recommendations

- First launch offers **Continue as Guest** before asking users to connect providers. Guest mode does not require Google or Spotify sign-in and can be used indefinitely.
- Public YouTube catalogue search uses the app publisher's configured YouTube Data API key without an account token. If this build has no catalogue key, the app opens YouTube's own public search page instead of forcing a Google login.
- Google sign-in remains optional and is only needed for account-specific features such as the user's private playlists and liked videos. Spotify search/playback remains optional and is used only after Spotify has been connected.
- Guest recommendations are generated locally from recent searches, online plays, local-library plays and liked local tracks, with recency decay and stronger weighting for repeated listening and likes. New guests start with starter categories (Hindi, Punjabi, lo-fi, workout, gaming and focus). The guest listening profile is stored in app-private preferences on the device and is not uploaded to an account or recommendation server.

## Provider playback boundaries

- YouTube playback remains inside the standard visible YouTube player with its normal controls and ads. The app does not extract audio, hide the player, or provide background playback for YouTube videos.
- Spotify playback is controlled through Spotify App Remote and is performed by the Spotify app. It is not a direct Spotify audio stream inside Media3. Spotify's Android App Remote SDK requires the Spotify app to be installed and the app to be registered in Spotify Developer Dashboard.
- Spotify Development Mode currently requires the developer to have Spotify Premium and limits a client ID to up to five authorized users; Spotify's developer rules can change. See Spotify's current developer access update before configuring access.
- This project is a personal/non-commercial integration. Spotify's platform terms prohibit using its platform to build a commercial streaming integration.

## Maintainer setup (not for app users)

This section is for the app publisher preparing builds. End users should never create provider developer projects, copy API keys, register signing fingerprints, or edit app configuration. Their setup should be limited to opening the app, choosing their Google or Spotify account, reviewing the provider's permission screen, and returning to Unified Music.

### Spotify

1. Create an app in [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
2. Select Android for the integration and register package name `com.vedansh.unifiedmusic` with the SHA-1 fingerprint printed by the GitHub Actions step **Print APK signing certificate SHA-1** for the APK run you install.
3. Add this exact redirect URI: `unifiedmusic://spotify/callback`.
4. Copy the app's **Client ID**. Open Unified Music, paste it into **Spotify App Setup**, and tap **Save Spotify setup**. The ID is stored on the device; no GitHub Actions secret is required. Do not enter or embed a Spotify Client Secret; this Android app uses PKCE.
5. Install the official Spotify app on the test phone and sign in to the account you want to use.
6. Tap **Connect Spotify** in Unified Music and approve access in Spotify's own authorization screen.

### YouTube / Google

1. In [Google Cloud Console](https://console.cloud.google.com/), create or select a project and enable **YouTube Data API v3**.
2. Configure the OAuth consent screen as **External** for personal Google accounts. While its publishing status is **Testing**, add every Google account you want to test under **Test users**. Accounts not on that list are blocked even when the Android login UI successfully shows an account chooser. Do not choose **Internal** unless every tester belongs to the same Google Workspace organization.
3. Register an Android OAuth client for package `com.vedansh.unifiedmusic` and the SHA-1 printed by the GitHub Actions step **Print APK signing certificate SHA-1** for the exact APK run you install.
4. Enable **YouTube Data API v3** in the same Google Cloud project. An API key can be added as the GitHub Actions repository secret `YOUTUBE_API_KEY` as a fallback; the app also sends the Google OAuth access token after the user approves access.
5. On first connect, tap **Connect Google**. The app now opens an explicit account chooser, requests the `youtube.readonly` permission, then verifies the selected account by calling `channels.list(mine=true)`. A green connected state is shown only after this API check succeeds.
6. For troubleshooting: Google status code `10` means the package/SHA-1 registration doesn't match; `403 access_denied` in OAuth test mode means the selected Google account must be added as a Test user; `accessNotConfigured` means YouTube Data API v3 is not enabled in the project; `quotaExceeded` means that project's YouTube quota has run out.

The SHA-1 value must match the build that is installed. GitHub-hosted runners normally receive a fresh temporary debug key, which changes the fingerprint between clean runs. To make Google login stable across future APKs, configure one maintainer-owned CI signing key once:

1. Generate a keystore locally with Java's `keytool` (keep the file and passwords private):
   ```sh
   keytool -genkeypair -v -keystore unifiedmusic-ci.jks -storetype JKS -alias unifiedmusic -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Base64-encode the keystore. On macOS/Linux: `base64 < unifiedmusic-ci.jks | tr -d '\n'`. On Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("unifiedmusic-ci.jks"))`.
3. In GitHub, open **Settings → Secrets and variables → Actions** and add these four repository secrets: `ANDROID_SIGNING_KEYSTORE_BASE64` (the encoded keystore), `ANDROID_SIGNING_KEYSTORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS` (for the command above, `unifiedmusic`), and `ANDROID_SIGNING_KEY_PASSWORD`.
4. Run the APK workflow again. It will sign the debug APK with that stable key and print the stable SHA-1/SHA-256. Register that SHA-1 once in the Android OAuth client for package `com.vedansh.unifiedmusic`.

Until all four signing secrets are configured, CI intentionally falls back to a temporary debug key and prints a notice. This one-time signing setup is for the app maintainer only; app users never need to do it. Never commit the keystore or its passwords to GitHub. For a locally built APK, register the certificate that actually signed that APK.

## Build

GitHub Actions downloads the official Spotify App Remote AAR, runs JVM unit tests, builds the debug APK, and uploads it as the `UnifiedMusic-debug-apk` artifact. The build works without provider secrets. Spotify's Client ID is entered once in the app's Spotify setup section. YouTube needs an Android OAuth client and YouTube Data API v3 enabled in the linked Google Cloud project. The YouTube API key is optional when Google OAuth is configured.

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
- Google access tokens are held in memory and obtained through Google Identity when required. The app no longer persists a bare "YouTube connected" boolean as proof of authorization; it verifies access with the YouTube Data API before showing the account as connected.
- Never commit API keys, OAuth secrets, refresh tokens or user credentials to the repository.

## Existing local player

The original `MainActivity` remains available for on-device audio, local playlists, liked local tracks, queue controls and Media3 playback. The account hub links to it without moving provider audio into the local player.
