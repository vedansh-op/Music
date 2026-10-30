#!/usr/bin/env sh
set -eu
mkdir -p app/libs
curl -fL --retry 3 \
  "https://github.com/spotify/android-sdk/releases/download/v0.8.0-appremote_v2.1.0-auth/spotify-app-remote-release-0.8.0.aar" \
  -o app/libs/spotify-app-remote-release-0.8.0.aar
echo "Spotify App Remote SDK installed in app/libs."
