package com.yourapp.youtubeplayer.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * A minimal Player wrapper that holds state for the MediaSession.
 * It doesn't play anything — the WebView handles actual playback.
 * We use SimpleBasePlayer from Media3 which provides all the boilerplate.
 */
@UnstableApi
class StateProxyPlayer(private val context: Context) : androidx.media3.common.SimpleBasePlayer(context.mainLooper) {

    private var isPlaying = false
    private var positionMs = 0L
    private var currentTitle: String? = null
    private var currentArtist: String? = null
    private var currentArtworkUri: Uri? = null

    fun updatePlaybackState(playing: Boolean, position: Long) {
        isPlaying = playing
        positionMs = position
        invalidateState()
    }

    fun updateMetadata(title: String?, artist: String?, thumbnailUrl: String?) {
        currentTitle = title
        currentArtist = artist
        currentArtworkUri = thumbnailUrl?.let { Uri.parse(it) }
        invalidateState()
    }

    override fun getState(): State {
        val mediaItemBuilder = MediaItemData.Builder(/* uid= */ "ace_player_current")

        val metadata = MediaMetadata.Builder()
            .setTitle(currentTitle ?: "ACE PLAYER")
            .setArtist(currentArtist ?: "")
            .setArtworkUri(currentArtworkUri)
            .build()

        mediaItemBuilder
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId("ace_player_current")
                    .setMediaMetadata(metadata)
                    .build()
            )
            .setMediaMetadata(metadata)

        val playlistItems = listOf(mediaItemBuilder.build())

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_MEDIA_ITEMS_METADATA
                    )
                    .build()
            )
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (currentTitle != null) Player.STATE_READY else Player.STATE_IDLE)
            .setContentPositionMs(positionMs)
            .setPlaylist(playlistItems)
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): com.google.common.util.concurrent.ListenableFuture<*> {
        isPlaying = playWhenReady
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        this.positionMs = positionMs
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }
}
