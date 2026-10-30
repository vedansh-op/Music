package com.vedansh.unifiedmusic

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class YouTubePlayerActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        if (videoId == null) { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF101114), surface = Color(0xFF1B1D22))) {
                Column(Modifier.fillMaxSize().background(Color(0xFF101114)).padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = true
                                webChromeClient = WebChromeClient()
                                webViewClient = WebViewClient()
                                val origin = "https://com.vedansh.unifiedmusic"
                                val html = """
                                    <!doctype html><html><head>
                                    <meta name="viewport" content="width=device-width, initial-scale=1">
                                    <meta name="referrer" content="strict-origin-when-cross-origin">
                                    <style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style>
                                    </head><body>
                                    <iframe src="https://www.youtube.com/embed/$videoId?playsinline=1&rel=0&origin=$origin"
                                      title="YouTube player"
                                      allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                                      referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
                                    </body></html>
                                """.trimIndent()
                                loadDataWithBaseURL(origin, html, "text/html", "UTF-8", null)
                            }
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Playback stays in YouTube's visible player. Audio extraction and background playback are not provided.",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO_ID = "youtube_video_id"
        const val EXTRA_TITLE = "youtube_video_title"
    }
}
