package com.yourapp.youtubeplayer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var playerReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_host)

        // Acquire a partial wake lock to keep CPU alive during playback
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AcePlayer::ActivityWakeLock"
        ).apply { acquire() }

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
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Keep WebView alive in background
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }

        webView.addJavascriptInterface(PlayerBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                PlaybackService.currentCommand?.let { handleServiceCommand(it) }
            }
        }

        webView.webChromeClient = WebChromeClient()

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
            is PlaybackService.PlayerCommand.AutoPlay -> {
                // Auto-play first track when Android Auto connects
                if (playerReady) {
                    webView.evaluateJavascript("autoPlayFirst();", null)
                } else {
                    // Queue it — will fire when player ready
                    activityScope.launch {
                        while (!playerReady) { delay(500L) }
                        runOnUiThread {
                            webView.evaluateJavascript("autoPlayFirst();", null)
                        }
                    }
                }
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
            playerReady = true
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

    // DO NOT pause WebView when activity goes to background — keep audio alive
    override fun onPause() {
        super.onPause()
        // Intentionally NOT calling webView.onPause() to keep audio playing
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        positionUpdateJob?.cancel()
        PlaybackService.commandListener = null
        wakeLock?.let { if (it.isHeld) it.release() }
        webView.destroy()
        super.onDestroy()
    }
}
