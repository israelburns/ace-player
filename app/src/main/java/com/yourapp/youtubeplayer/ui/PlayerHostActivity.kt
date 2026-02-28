package com.yourapp.youtubeplayer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.yourapp.youtubeplayer.R
import com.yourapp.youtubeplayer.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerHostActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var positionUpdateJob: Job? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_host)

        // Start the PlaybackService for media session / notification / Android Auto
        val serviceIntent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(PlayerBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                PlaybackService.currentCommand?.let { handleServiceCommand(it) }
            }
        }

        webView.webChromeClient = WebChromeClient()

        // Enable WebView debugging for troubleshooting
        WebView.setWebContentsDebuggingEnabled(true)

        // Load from GCP server — YouTube IFrame API requires HTTPS origin
        webView.loadUrl("https://ace-taskmaster.duckdns.org/player")

        // Listen for commands from PlaybackService (e.g. Android Auto controls)
        PlaybackService.commandListener = { command ->
            runOnUiThread { handleServiceCommand(command) }
        }

        startPositionUpdates()
    }

    private fun handleServiceCommand(command: PlaybackService.PlayerCommand) {
        when (command) {
            is PlaybackService.PlayerCommand.Play -> {
                webView.evaluateJavascript("if(yt)yt.playVideo();", null)
            }
            is PlaybackService.PlayerCommand.Pause -> {
                webView.evaluateJavascript("if(yt)yt.pauseVideo();", null)
            }
            is PlaybackService.PlayerCommand.Next -> {
                webView.evaluateJavascript("nextTrack();", null)
            }
            is PlaybackService.PlayerCommand.Previous -> {
                webView.evaluateJavascript("previousTrack();", null)
            }
            is PlaybackService.PlayerCommand.Seek -> {
                webView.evaluateJavascript(
                    "if(yt)yt.seekTo(${command.positionMs / 1000},true);", null
                )
            }
            is PlaybackService.PlayerCommand.LoadVideo -> {
                webView.evaluateJavascript(
                    "if(yt)yt.loadVideoById('${command.videoId}');", null
                )
            }
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob = activityScope.launch {
            while (isActive) {
                webView.evaluateJavascript("yt?yt.getCurrentTime():0") { result ->
                    val posSec = result?.toDoubleOrNull() ?: 0.0
                    val posMs = (posSec * 1000).toLong()
                    webView.evaluateJavascript("yt?yt.getPlayerState():-1") { stateResult ->
                        val state = stateResult?.toIntOrNull() ?: -1
                        val isPlaying = state == 1
                        PlaybackService.instance?.updatePlaybackState(isPlaying, posMs)
                    }
                }
                delay(500L)
            }
        }
    }

    inner class PlayerBridge {
        @JavascriptInterface
        fun onPlayerReady() {
            // Player is initialized
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            runOnUiThread {
                val isPlaying = state == 1
                PlaybackService.instance?.updatePlaybackState(isPlaying, 0)
            }
        }

        @JavascriptInterface
        fun onVideoInfo(videoId: String, title: String, author: String) {
            runOnUiThread {
                val thumb = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                PlaybackService.instance?.updateMetadata(title, author, thumb)
            }
        }

        @JavascriptInterface
        fun onError(errorCode: Int) {
            // Handled in JS (auto-skip)
        }
    }

    override fun onDestroy() {
        positionUpdateJob?.cancel()
        PlaybackService.commandListener = null
        webView.destroy()
        super.onDestroy()
    }
}
