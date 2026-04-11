package com.yourapp.youtubeplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream

@UnstableApi
class StateProxyPlayer(private val context: Context) : androidx.media3.common.SimpleBasePlayer(context.mainLooper) {

    private var isPlaying = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var currentTitle: String? = null
    private var currentArtist: String? = null
    private var currentArtworkUri: Uri? = null
    private var currentArtworkData: ByteArray? = null

    // Callbacks for transport controls
    var onNextRequested: (() -> Unit)? = null
    var onPreviousRequested: (() -> Unit)? = null
    var onSeekRequested: ((Long) -> Unit)? = null

    fun updatePlaybackState(playing: Boolean, position: Long) {
        isPlaying = playing
        positionMs = position
        invalidateState()
    }

    fun updateDuration(duration: Long) {
        durationMs = duration
    }

    fun updateMetadata(title: String?, artist: String?, thumbnailUrl: String?) {
        currentTitle = title
        currentArtist = artist
        currentArtworkUri = thumbnailUrl?.let { Uri.parse(it) }
        invalidateState()
    }

    fun updateArtworkBitmap(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        currentArtworkData = stream.toByteArray()
        invalidateState()
    }

    override fun getState(): State {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(currentTitle ?: "ACE PLAYER")
            .setArtist(currentArtist ?: "")
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(currentArtworkUri)

        if (currentArtworkData != null) {
            metadataBuilder.setArtworkData(currentArtworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        val metadata = metadataBuilder.build()

        // Build a 3-item fake playlist so next/prev are always valid
        // Index 0 = "previous", Index 1 = current (active), Index 2 = "next"
        val prevItem = MediaItemData.Builder("ace_prev")
            .setMediaItem(MediaItem.Builder().setMediaId("ace_prev").setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .build()
        val currentItem = MediaItemData.Builder("ace_current")
            .setMediaItem(MediaItem.Builder().setMediaId("ace_current").setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .build()
        val nextItem = MediaItemData.Builder("ace_next")
            .setMediaItem(MediaItem.Builder().setMediaId("ace_next").setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .build()

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
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
            .setPlaylist(listOf(prevItem, currentItem, nextItem))
            .setCurrentMediaItemIndex(1) // Always at middle item
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): com.google.common.util.concurrent.ListenableFuture<*> {
        isPlaying = playWhenReady
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        when (mediaItemIndex) {
            0 -> onPreviousRequested?.invoke()    // Sought to "previous" item
            2 -> onNextRequested?.invoke()         // Sought to "next" item
            else -> {
                // Seek within current track
                this.positionMs = positionMs
                onSeekRequested?.invoke(positionMs)
            }
        }
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }
}
