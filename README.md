# Unified Music — Android music player

A native Android music-player foundation built with Kotlin, Jetpack Compose, and AndroidX Media3.

## Implemented in the current milestone

- Reads on-device audio through Android MediaStore after the user grants audio permission.
- Performs the MediaStore query on an IO dispatcher rather than blocking the UI thread.
- Plays local tracks through an ExoPlayer hosted in a MediaSessionService.
- Builds a queue from the local library, exposes a queue screen, and provides previous/next playback controls.
- Includes a mini-player and a dedicated Now Playing screen with seeking, shuffle, and repeat modes.
- Saves liked local tracks with Preferences DataStore and filters the Library to liked songs.
- Creates, persists, and deletes local playlists; add/remove device tracks and play a playlist-specific queue.
- Displays local album artwork when Android MediaStore provides it, including the mini-player, queue, and Now Playing screen.
- Searches local track title, artist, and album metadata.
- Builds a debug APK through GitHub Actions and runs JVM unit tests before packaging.

## Current limitations

- Demo catalogue rows are UI-only and cannot be played.
- YouTube Music and Spotify account linking, provider-backed streaming, lyrics, and online downloads are not implemented yet.
- Playlists and favourites are currently local-only and reference tracks found by Android MediaStore.
- A successful CI build verifies compilation and unit tests; it does not replace installing and testing the APK on a physical Android device.

## Build without Android Studio

1. Open the repository's **Actions** tab.
2. Select the latest successful **Build Android APK** workflow run.
3. Download the **UnifiedMusic-debug-apk** artifact and extract **app-debug.apk**.
4. Transfer the APK to an Android phone and install it. Android may ask you to allow installation from the browser or file manager used to open it.

The artifact is a debug APK, not a Play Store release. The workflow retains each artifact for 14 days.

## YouTube integration note

The app should use documented and authorized provider capabilities only. The official YouTube API policies prohibit audio separation and background playback of YouTube audiovisual content through API clients. A future provider integration needs a compliant playback strategy and must not collect a user's Google password.

## Architecture direction

Keep provider adapters behind catalog/domain interfaces instead of calling APIs from composables. Treat local playback and any online provider as separate sources so unsupported demo items can never masquerade as playable tracks.
