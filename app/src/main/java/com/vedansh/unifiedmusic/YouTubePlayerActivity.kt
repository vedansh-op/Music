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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class YouTubePlayerActivity : ComponentActivity() {
    private var playerWebView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        if (videoId == null) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF101114),
                    surface = Color(0xFF1B1D22)
                )
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101114))
                        .padding(16.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp)
                            .aspectRatio(16f / 9f),
                        factory = { context ->
                            WebView(context).apply {
                                playerWebView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = true
                                webChromeClient = WebChromeClient()
                                webViewClient = WebViewClient()
                                val origin = "https://com.vedansh.unifiedmusic"
                                val html = """
                                    <!doctype html>
                                    <html>
                                      <head>
                                        <meta name="viewport" content="width=device-width, initial-scale=1">
                                        <meta name="referrer" content="strict-origin-when-cross-origin">
                                        <style>
                                          html, body, #player { margin:0; padding:0; width:100%; height:100%; background:#000; }
                                        </style>
                                      </head>
                                      <body>
                                        <div id="player"></div>
                                        <script>
                                          var youtubePlayer;
                                          function onYouTubeIframeAPIReady() {
                                            youtubePlayer = new YT.Player('player', {
                                              width: '100%',
                                              height: '100%',
                                              videoId: '$videoId',
                                              playerVars: {
                                                playsinline: 1,
                                                rel: 0,
                                                origin: '$origin'
                                              }
                                            });
                                          }
                                        </script>
                                        <script src="https://www.youtube.com/iframe_api"></script>
                                      </body>
                                    </html>
                                """.trimIndent()
                                loadDataWithBaseURL(origin, html, "text/html", "UTF-8", null)
                            }
                        },
                        update = { playerWebView = it }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Playback stays in YouTube's visible player with YouTube controls and ads. " +
                            "It pauses when this screen leaves the foreground; audio extraction is not provided.",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    override fun onPause() {
        playerWebView?.evaluateJavascript(
            "if (window.youtubePlayer && window.youtubePlayer.pauseVideo) { window.youtubePlayer.pauseVideo(); }",
            null
        )
        playerWebView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        playerWebView?.onResume()
    }

    override fun onDestroy() {
        playerWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        playerWebView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "youtube_video_id"
        const val EXTRA_TITLE = "youtube_video_title"
    }
}
