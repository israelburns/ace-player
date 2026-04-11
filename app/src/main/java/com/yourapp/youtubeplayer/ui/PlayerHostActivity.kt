package com.yourapp.youtubeplayer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
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
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Keep WebView alive in background
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }

        // CRITICAL: Tell system to keep WebView renderer alive even when activity not visible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false  // false = do NOT bind to activity visibility
            )
        }

        // Keep screen on (dimmed) to prevent WebView audio from being killed
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView.addJavascriptInterface(PlayerBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Restore queue from SharedPreferences
                val savedQueue = getSharedPreferences("ace_player", MODE_PRIVATE)
                    .getString("queue", null)
                if (savedQueue != null) {
                    val escaped = savedQueue.replace("\\", "\\\\").replace("'", "\\'")
                    view?.evaluateJavascript(
                        "try{PL.queue=JSON.parse('$escaped');updateQueueBadge();if(cur==='queue')renderPL();}catch(e){}", null
                    )
                    // Sync to PlaybackService for Android Auto
                    PlaybackService.instance?.updateQueue(savedQueue)
                }
                PlaybackService.currentCommand?.let { handleServiceCommand(it) }
            }
        }

        webView.webChromeClient = WebChromeClient()

        // Load from GCP server — YouTube IFrame API requires HTTPS origin
        // Cache-bust to ensure latest JS (native audio routing)
        webView.loadUrl("https://ace-taskmaster.duckdns.org/player?v=" + System.currentTimeMillis())

        // Request battery optimization exemption so playback survives screen off
        requestBatteryExemption()

        // Listen for commands from PlaybackService (e.g. Android Auto controls)
        PlaybackService.commandListener = { command ->
            runOnUiThread { handleServiceCommand(command) }
        }

        startPositionUpdates()
    }

    private fun handleServiceCommand(command: PlaybackService.PlayerCommand) {
        val svc = PlaybackService.instance
        when (command) {
            is PlaybackService.PlayerCommand.Play -> {
                if (svc?.isNativeAudioActive == true) {
                    svc.nativePlay()
                } else {
                    webView.evaluateJavascript(
                        "if(typeof useDirectAudio!=='undefined'&&useDirectAudio){ap.play();}else if(yt){yt.playVideo();}", null
                    )
                }
            }
            is PlaybackService.PlayerCommand.Pause -> {
                if (svc?.isNativeAudioActive == true) {
                    svc.nativePause()
                } else {
                    webView.evaluateJavascript(
                        "if(typeof useDirectAudio!=='undefined'&&useDirectAudio){ap.pause();}else if(yt){yt.pauseVideo();}", null
                    )
                }
            }
            is PlaybackService.PlayerCommand.Next -> {
                webView.evaluateJavascript("next();", null)
            }
            is PlaybackService.PlayerCommand.Previous -> {
                webView.evaluateJavascript("prev();", null)
            }
            is PlaybackService.PlayerCommand.Seek -> {
                if (svc?.isNativeAudioActive == true) {
                    svc.nativeSeek(command.positionMs)
                } else {
                    webView.evaluateJavascript(
                        "if(typeof useDirectAudio!=='undefined'&&useDirectAudio){ap.currentTime=${command.positionMs / 1000};}else if(yt){yt.seekTo(${command.positionMs / 1000},true);}", null
                    )
                }
            }
            is PlaybackService.PlayerCommand.LoadVideo -> {
                webView.evaluateJavascript(
                    "if(yt)yt.loadVideoById('${command.videoId}');", null
                )
            }
            is PlaybackService.PlayerCommand.LoadPlaylist -> {
                webView.evaluateJavascript(
                    "if(typeof switchPL==='function'){switchPL('${command.key}');play(0);}", null
                )
            }
            is PlaybackService.PlayerCommand.PlayIndex -> {
                webView.evaluateJavascript("play(${command.index});", null)
            }
            is PlaybackService.PlayerCommand.AutoPlay -> {
                if (playerReady) {
                    webView.evaluateJavascript("play(0);", null)
                } else {
                    activityScope.launch {
                        while (!playerReady) { delay(500L) }
                        runOnUiThread {
                            webView.evaluateJavascript("play(0);", null)
                        }
                    }
                }
            }
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob = activityScope.launch {
            while (isActive) {
                val svc = PlaybackService.instance
                if (svc?.isNativeAudioActive == true) {
                    // Native ExoPlayer — read position directly, no WebView needed
                    svc.updatePlaybackState(svc.isNativePlaying(), svc.getNativePosition())
                    // Sync position back to JS UI
                    val posSec = svc.getNativePosition() / 1000.0
                    val durSec = svc.getNativeDuration() / 1000.0
                    val playing = svc.isNativePlaying()
                    webView.evaluateJavascript(
                        "if(typeof _nativeSync==='function')_nativeSync($posSec,$durSec,$playing);", null
                    )
                } else {
                    // WebView audio — poll JS for position
                    webView.evaluateJavascript(
                        "(function(){" +
                        "if(typeof useDirectAudio!=='undefined'&&useDirectAudio&&ap&&ap.src){" +
                        "return JSON.stringify({pos:ap.currentTime||0,playing:!ap.paused});" +
                        "}else if(yt){" +
                        "return JSON.stringify({pos:yt.getCurrentTime()||0,playing:yt.getPlayerState()===1});" +
                        "}else{return JSON.stringify({pos:0,playing:false});}" +
                        "})()"
                    ) { result ->
                        try {
                            val json = result?.trim('"')?.replace("\\\"", "\"")
                                ?.replace("\\\\", "\\")
                            if (json != null && json.startsWith("{")) {
                                val posMatch = Regex("\"pos\":(\\d+\\.?\\d*)").find(json)
                                val playMatch = Regex("\"playing\":(true|false)").find(json)
                                val posSec = posMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                                val isPlaying = playMatch?.groupValues?.get(1) == "true"
                                val posMs = (posSec * 1000).toLong()
                                PlaybackService.instance?.updatePlaybackState(isPlaying, posMs)
                            }
                        } catch (_: Exception) {}
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
                val svc = PlaybackService.instance
                if (svc?.isNativeAudioActive != true) {
                    val isPlaying = state == 1
                    svc?.updatePlaybackState(isPlaying, 0)
                }
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

        @JavascriptInterface
        fun playNativeAudio(url: String) {
            android.util.Log.i("AcePlayer", "NATIVE AUDIO requested: ${url.take(80)}")
            runOnUiThread {
                PlaybackService.instance?.playNativeAudio(url)
            }
        }

        @JavascriptInterface
        fun pauseNativeAudio() {
            runOnUiThread { PlaybackService.instance?.nativePause() }
        }

        @JavascriptInterface
        fun resumeNativeAudio() {
            runOnUiThread { PlaybackService.instance?.nativePlay() }
        }

        @JavascriptInterface
        fun seekNativeAudio(positionSec: Double) {
            runOnUiThread { PlaybackService.instance?.nativeSeek((positionSec * 1000).toLong()) }
        }

        @JavascriptInterface
        fun saveQueue(json: String) {
            getSharedPreferences("ace_player", MODE_PRIVATE)
                .edit().putString("queue", json).apply()
            // Sync queue to PlaybackService for Android Auto browse tree
            runOnUiThread {
                PlaybackService.instance?.updateQueue(json)
            }
        }

        @JavascriptInterface
        fun stopNativeAudio() {
            runOnUiThread {
                PlaybackService.instance?.stopNativeAudio()
            }
        }

        @JavascriptInterface
        fun isNativeAudioActive(): Boolean {
            return PlaybackService.instance?.isNativeAudioActive == true
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                try { startActivity(intent) } catch (_: Exception) {}
            }
        }
    }

    // Move to background instead of closing when user presses back
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // DO NOT pause WebView when activity goes to background — keep audio alive
    override fun onPause() {
        super.onPause()
        // Intentionally NOT calling webView.onPause() to keep audio playing
    }

    override fun onStop() {
        super.onStop()
        // When native audio is active, don't touch WebView — ExoPlayer handles everything
        if (PlaybackService.instance?.isNativeAudioActive == true) return
        // Samsung aggressively pauses WebView in onStop — explicitly resume it
        webView.onResume()
        webView.evaluateJavascript("if(yt&&yt.getPlayerState()===2)yt.playVideo();", null)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    // Do NOT destroy WebView when task is removed — keep audio alive
    override fun onDestroy() {
        positionUpdateJob?.cancel()
        PlaybackService.commandListener = null
        wakeLock?.let { if (it.isHeld) it.release() }
        // Only destroy WebView if app is truly finishing (not just being sent to background)
        if (isFinishing) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
