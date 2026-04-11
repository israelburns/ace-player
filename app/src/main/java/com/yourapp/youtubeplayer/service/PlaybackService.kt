package com.yourapp.youtubeplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yourapp.youtubeplayer.R
import com.yourapp.youtubeplayer.player.StateProxyPlayer
import com.yourapp.youtubeplayer.ui.PlayerHostActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var stateProxyPlayer: StateProxyPlayer
    private var nativePlayer: ExoPlayer? = null
    var isNativeAudioActive = false
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ROOT_ID = "root"

        var currentCommand: PlayerCommand? = null
        var commandListener: ((PlayerCommand) -> Unit)? = null

        var instance: PlaybackService? = null
        var autoConnected = false
    }

    sealed class PlayerCommand {
        object Play : PlayerCommand()
        object Pause : PlayerCommand()
        object Next : PlayerCommand()
        object Previous : PlayerCommand()
        data class Seek(val positionMs: Long) : PlayerCommand()
        data class LoadVideo(val videoId: String) : PlayerCommand()
        data class LoadPlaylist(val key: String) : PlayerCommand()
        object AutoPlay : PlayerCommand()
    }

    private val playlistNames = listOf(
        "Top 40", "Today's Hits", "Ace Burns", "Drizzy", "Bars", "Heat", "Queens",
        "Afro", "Smooth", "Soul", "Vibes", "Santana",
        "Meditate", "Workout", "Skating", "Oldies",
        "Unexpected", "Emerging", "Caribbean"
    )

    // Representative YouTube video IDs for playlist artwork (Android Auto icons)
    private val playlistThumbnails = listOf(
        "ETPBnOlNeOw", "NYH6Oa4PXlY", "ogXC9YU_hmM", "V7UgPHjN9qE", "H58vbez_m4E",
        "ETPBnOlNeOw", "hsm4poTWjMs", "dNt1QR1ecuM", "Z9eMk051dYg",
        "4TYv2PhG89A", "7wfYIMyS_dI", "6Whgn_iE5uc", "w3aAKiZ0euE",
        "6ONRf7h3Mdk", "0OfSyl9gkWM", "7ll7ocQbH_k",
        "Eix8FGWaLn0", "kf0YhDDWl7c", "yMi_gAl3APE"
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLocks()

        // Audio attributes for music — routes to Bluetooth A2DP and requests focus
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Native ExoPlayer for background-safe audio playback
        nativePlayer = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (isNativeAudioActive) {
                            val playing = playWhenReady && playbackState == Player.STATE_READY
                            stateProxyPlayer.updatePlaybackState(playing, currentPosition)
                        }
                        // Track ended — tell JS to advance to next
                        if (playbackState == Player.STATE_ENDED && isNativeAudioActive) {
                            sendCommandToActivity(PlayerCommand.Next)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isNativeAudioActive) {
                            stateProxyPlayer.updatePlaybackState(isPlaying, currentPosition)
                        }
                    }
                })
            }

        stateProxyPlayer = StateProxyPlayer(applicationContext)

        stateProxyPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (isNativeAudioActive) {
                    // Route play/pause to native player
                    nativePlayer?.playWhenReady = playWhenReady
                } else {
                    if (playWhenReady) {
                        sendCommandToActivity(PlayerCommand.Play)
                    } else {
                        sendCommandToActivity(PlayerCommand.Pause)
                    }
                }
            }
        })

        // Wire up next/prev/seek from Android Auto through StateProxyPlayer
        stateProxyPlayer.onNextRequested = { sendCommandToActivity(PlayerCommand.Next) }
        stateProxyPlayer.onPreviousRequested = { sendCommandToActivity(PlayerCommand.Previous) }
        stateProxyPlayer.onSeekRequested = { posMs -> sendCommandToActivity(PlayerCommand.Seek(posMs)) }

        val sessionExtras = android.os.Bundle().apply {
            putBoolean("android.media.session.SLOT_RESERVATION_SKIP_TO_NEXT", true)
            putBoolean("android.media.session.SLOT_RESERVATION_SKIP_TO_PREV", true)
        }

        // Set session activity so Android Auto can launch the app
        val sessionActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerHostActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            stateProxyPlayer,
            object : MediaLibrarySession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    // Track Android Auto connection
                    val packageName = controller.packageName
                    if (packageName.contains("android.auto") ||
                        packageName.contains("gearhead") ||
                        packageName.contains("car") ||
                        packageName.contains("automotive") ||
                        packageName.contains("projection") ||
                        packageName.contains("media.session")) {
                        autoConnected = true
                    }

                    // CRITICAL: Use DEFAULT_SESSION_AND_LIBRARY_COMMANDS so the legacy
                    // MediaBrowserService bridge grants browse access to Android Auto
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().build()
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().build()

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .build()
                }

                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val rootItem = MediaItem.Builder()
                        .setMediaId(ROOT_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setIsPlayable(false)
                                .setIsBrowsable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .setTitle("ACE PLAYER")
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
                }

                override fun onGetChildren(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    if (parentId == ROOT_ID) {
                        val items = playlistNames.mapIndexed { index, name ->
                            val thumbId = playlistThumbnails.getOrElse(index) { "ogXC9YU_hmM" }
                            MediaItem.Builder()
                                .setMediaId("playlist_$index")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(name)
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                        .setArtworkUri(ArtworkProvider.getArtworkUri(thumbId))
                                        .build()
                                )
                                .build()
                        }
                        return Futures.immediateFuture(
                            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                        )
                    }
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(ImmutableList.of(), params)
                    )
                }

                override fun onGetItem(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    mediaId: String
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val index = if (mediaId.startsWith("playlist_")) {
                        mediaId.removePrefix("playlist_").toIntOrNull() ?: 0
                    } else 0
                    val item = MediaItem.Builder()
                        .setMediaId(mediaId)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(playlistNames.getOrElse(index) { "ACE PLAYER" })
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(LibraryResult.ofItem(item, null))
                }

                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    // Android Auto sends this when user taps a playlist
                    val selectedId = mediaItems.firstOrNull()?.mediaId ?: ""
                    val playlistIndex = if (selectedId.startsWith("playlist_")) {
                        selectedId.removePrefix("playlist_").toIntOrNull() ?: 0
                    } else 0

                    // Map playlist index to JS playlist key
                    val playlistKeys = listOf(
                        "top40", "todays_hits", "ace", "drake", "bars", "heat", "queens",
                        "afro", "smooth", "soul", "vibes", "santana",
                        "meditation", "workout", "skating", "oldies",
                        "unexpected", "emerging", "caribbean"
                    )
                    val key = playlistKeys.getOrElse(playlistIndex) { "top40" }

                    // Tell the WebView to switch playlist and play
                    sendCommandToActivity(PlayerCommand.LoadPlaylist(key))

                    // Return the media item so AA doesn't error out
                    val resolvedItem = MediaItem.Builder()
                        .setMediaId(selectedId)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(playlistNames.getOrElse(playlistIndex) { "ACE PLAYER" })
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(mutableListOf(resolvedItem))
                }

                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    // Auto-play on media button from car
                    if (!autoConnected) {
                        autoConnected = true
                        sendCommandToActivity(PlayerCommand.AutoPlay)
                        return true
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            }
        ).setSessionExtras(sessionExtras)
         .setSessionActivity(sessionActivityIntent)
         .build()

        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun acquireWakeLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AcePlayer::PlaybackWakeLock"
        ).apply { acquire() }

        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "AcePlayer::WifiLock"
        ).apply { acquire() }
    }

    private fun releaseWakeLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, PlayerHostActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ACE PLAYER")
            .setContentText("Playing")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun sendCommandToActivity(command: PlayerCommand) {
        currentCommand = command
        commandListener?.invoke(command)
    }

    fun updatePlaybackState(isPlaying: Boolean, positionMs: Long) {
        stateProxyPlayer.updatePlaybackState(isPlaying, positionMs)
    }

    fun updateMetadata(title: String?, artist: String?, thumbnailUrl: String?) {
        // Extract video ID from thumbnail URL for content:// URI
        val videoId = thumbnailUrl?.let {
            Regex("/vi/([^/]+)/").find(it)?.groupValues?.get(1)
        }
        val contentUri = videoId?.let { ArtworkProvider.getArtworkUri(it) }
        stateProxyPlayer.updateMetadata(title, artist, contentUri?.toString())

        // Pre-cache artwork so Android Auto can read it immediately
        if (videoId != null) {
            serviceScope.launch {
                try {
                    // Trigger the ContentProvider to cache the image
                    val url = URL("https://img.youtube.com/vi/$videoId/hqdefault.jpg")
                    val bitmap = BitmapFactory.decodeStream(url.openStream())
                    if (bitmap != null) {
                        stateProxyPlayer.updateArtworkBitmap(bitmap)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // Native ExoPlayer audio — bulletproof background playback
    fun playNativeAudio(url: String) {
        nativePlayer?.let { player ->
            isNativeAudioActive = true
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun nativePlay() {
        if (isNativeAudioActive) nativePlayer?.play()
    }

    fun nativePause() {
        if (isNativeAudioActive) nativePlayer?.pause()
    }

    fun nativeSeek(positionMs: Long) {
        if (isNativeAudioActive) nativePlayer?.seekTo(positionMs)
    }

    fun stopNativeAudio() {
        isNativeAudioActive = false
        nativePlayer?.stop()
    }

    fun getNativePosition(): Long = if (isNativeAudioActive) nativePlayer?.currentPosition ?: 0 else 0
    fun getNativeDuration(): Long = if (isNativeAudioActive) nativePlayer?.duration ?: 0 else 0
    fun isNativePlaying(): Boolean = isNativeAudioActive && (nativePlayer?.isPlaying == true)

    // Keep service alive when user swipes app away
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do NOT call super or stopSelf — keep playing
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        instance = null
        autoConnected = false
        isNativeAudioActive = false
        nativePlayer?.release()
        nativePlayer = null
        releaseWakeLocks()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
