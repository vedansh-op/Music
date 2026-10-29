# Unified Music — Android music player

A native Android music player built with Kotlin, Jetpack Compose, and AndroidX Media3.

## Implemented

- Scans and plays audio stored on the device through Android MediaStore.
- Performs library scanning on an IO dispatcher so large libraries do not block the UI.
- Provides queue browsing, previous/next, seek, shuffle/repeat, mini-player, and full Now Playing screen.
- Displays album artwork when provided by Android MediaStore or Audius.
- Saves liked local tracks and user-created local playlists using Preferences DataStore.
- Supports create/delete playlists, add/remove local tracks, and playlist-specific playback queues.
- Searches the local library and Audius's public catalogue, then streams eligible Audius tracks inside the app's Media3 player.
- GitHub Actions runs JVM unit tests before compiling and uploading a debug APK.

## Online music provider

The first in-app streaming adapter is Audius, using the provider's track-search and track-stream routes. Search results are marked **AUDIUS** and streamed directly inside the player. Only tracks marked streamable by the provider are offered. Provider availability and individual track availability can change.

For improved API stability/rate limits, create an Audius API key and add it as the GitHub Actions repository secret **AUDIUS_API_KEY**. The build injects it into the app and sends it in request headers. A public discovery-provider endpoint is tried as a fallback. Do not add an Audius bearer token to the Android app.

**This is not YouTube Music integration.** YouTube's official API policies do not allow an API client to separate audio from videos or enable background playback of YouTube video content. A future YouTube feature must use an authorized, policy-compliant playback experience. Spotify playback also needs its own authorized SDK/integration.

## Current limitations

- Audius search/playback depends on the provider and network; individual tracks may not be available in every region.
- Favourites and playlists are local to this installation and store MediaStore track IDs; they do not sync with Audius or another device.
- Lyrics, recommendations, online playlist sync, offline downloads, and Audius account sign-in are not implemented.
- CI confirms compilation and JVM unit tests; it does not replace installing the APK on a physical device and trying local/streamed tracks.
- The artifact is a debug APK, not a Play Store release, and is retained for 14 days.

## Download the APK without Android Studio

1. Open the repository's **Actions** tab.
2. Open the latest successful **Build Android APK** run.
3. Download the **UnifiedMusic-debug-apk** artifact and extract **app-debug.apk**.
4. Transfer it to your Android phone and allow installation from the file manager/browser if Android prompts you.

## Structure

Provider requests live in AudiusCatalog; Compose screens call the catalog interface rather than networking directly. The local library and online catalogue remain separate track sources so a non-playable demo row cannot masquerade as a stream.
