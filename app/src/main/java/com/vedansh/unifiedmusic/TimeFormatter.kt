package com.vedansh.unifiedmusic

import java.util.Locale

/** Formats non-negative playback milliseconds as m:ss, or h:mm:ss for long tracks. */
internal fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val seconds = totalSeconds % 60L
    val totalMinutes = totalSeconds / 60L
    return if (totalMinutes >= 60L) {
        String.format(Locale.US, "%d:%02d:%02d", totalMinutes / 60L, totalMinutes % 60L, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", totalMinutes, seconds)
    }
}
