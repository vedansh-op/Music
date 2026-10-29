# Unified Music — Android starter

A native Android-first foundation for a unified music app.

## Current status

This is **Milestone 0 / app-shell starter**, not a finished music player and not yet a verified APK.

Included:
- Kotlin + Jetpack Compose app shell
- Dark interface with a lime accent
- Search field and source filter visual chips
- Shared `Track`, `TrackSource`, and `TrackAvailability` models
- Demo catalog for exercising search UI
- AndroidX Media3 `MediaSessionService` foundation
- Room and DataStore dependencies reserved for persistence work
- Android 10+ minimum SDK

Not yet implemented:
- Real YouTube or Spotify account connection
- Provider-backed search
- Actual local media scanning and playback controls
- Queue persistence, favorites, playlists, lyrics
- Verified build, device testing, and release signing

## Open in Android Studio

1. Install Android Studio and a JDK 17.
2. Open this folder (`UnifiedMusicAndroid`) in Android Studio.
3. Allow Gradle sync and install Android SDK Platform 35 if prompted.
4. Run the `app` configuration on an Android 10+ device or emulator.

To create a debug APK after a successful sync:
`./gradlew assembleDebug`

The APK should appear at:
`app/build/outputs/apk/debug/app-debug.apk`

## Architecture rule

The UI must not call third-party music APIs directly. Add one adapter per provider behind the catalog/domain interfaces. A provider is enabled only after its current authorization, playback, attribution, and distribution rules have been verified.

## Next implementation slice

1. Add a MediaStore local-audio repository and permission flow.
2. Connect the UI to a `MediaController` for play/pause/seek/queue.
3. Add Room entities and DataStore preferences.
4. Add provider adapters only for approved capabilities.
5. Add unit and device tests before calling the APK installable.


## Build without Android Studio (GitHub Actions)

1. Create a new **empty** GitHub repository.
2. Upload the contents of this project ZIP to the repository (not the ZIP file itself).
3. Open the repository's **Actions** tab.
4. Select **Build Android APK** and choose **Run workflow**, or push to `main`.
5. Open the completed workflow run and download the `UnifiedMusic-debug-apk` artifact.
6. Extract the downloaded artifact to get `app-debug.apk`, then transfer it to your Android phone and install it.

The workflow builds in GitHub's hosted Linux environment. The APK is a debug build, not a Play Store release.
