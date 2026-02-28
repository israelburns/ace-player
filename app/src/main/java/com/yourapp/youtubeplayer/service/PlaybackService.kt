package com.yourapp.youtubeplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yourapp.youtubeplayer.R
import com.yourapp.youtubeplayer.player.StateProxyPlayer
import com.yourapp.youtubeplayer.ui.PlayerHostActivity

class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var stateProxyPlayer: StateProxyPlayer

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ROOT_ID = "root"

        var currentCommand: PlayerCommand? = null
        var commandListener: ((PlayerCommand) -> Unit)? = null

        // Static ref for activity to push state updates
        var instance: PlaybackService? = null
    }

    sealed class PlayerCommand {
        object Play : PlayerCommand()
        object Pause : PlayerCommand()
        object Next : PlayerCommand()
        object Previous : PlayerCommand()
        data class Seek(val positionMs: Long) : PlayerCommand()
        data class LoadVideo(val videoId: String) : PlayerCommand()
    }

    // Hardcoded playlist names matching the HTML
    private val playlistNames = listOf(
        "Ace Burns", "Drizzy", "Bars", "Heat", "Queens",
        "Afro", "Smooth", "Soul", "Vibes", "Santana"
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        stateProxyPlayer = StateProxyPlayer(applicationContext)

        stateProxyPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    sendCommandToActivity(PlayerCommand.Play)
                } else {
                    sendCommandToActivity(PlayerCommand.Pause)
                }
            }
        })

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            stateProxyPlayer,
            object : MediaLibrarySession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().build()
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
                            MediaItem.Builder()
                                .setMediaId("playlist_$index")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(name)
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
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
            }
        ).build()

        startForeground(NOTIFICATION_ID, createNotification())
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
        stateProxyPlayer.updateMetadata(title, artist, thumbnailUrl)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        instance = null
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
