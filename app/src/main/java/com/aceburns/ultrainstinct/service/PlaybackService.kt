package com.aceburns.ultrainstinct.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.aceburns.ultrainstinct.data.CatalogRepository
import com.aceburns.ultrainstinct.data.Playlist
import com.aceburns.ultrainstinct.data.Track
import com.aceburns.ultrainstinct.ui.PlayerHostActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// Local-file MediaLibraryService.
// - Uses ExoPlayer to play .opus files from getExternalFilesDir/Music/
// - Exposes a browsable tree to Android Auto: Root -> Playlist -> Track
class PlaybackService : MediaLibraryService() {

    companion object {
        const val ROOT_ID = "root"
        const val PLAYLIST_PREFIX = "playlist:"
        const val TRACK_PREFIX = "track:"
        var instance: PlaybackService? = null
    }

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        instance = this
        CatalogRepository.ensureLoaded(applicationContext)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .build()

        val sessionActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerHostActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep service alive so playback continues when user swipes app away
    }

    override fun onDestroy() {
        instance = null
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    // Helpers used by UI
    fun loadPlaylist(key: String, startIndex: Int = 0, autoPlay: Boolean = true) {
        val pl = CatalogRepository.getPlaylist(key) ?: return
        val items = pl.tracks.filter { it.isLocal }.map { buildMediaItem(it) }
        if (items.isEmpty()) return
        val safeIdx = startIndex.coerceIn(0, items.size - 1)
        player.setMediaItems(items, safeIdx, 0L)
        player.prepare()
        player.playWhenReady = autoPlay
    }

    private fun buildMediaItem(track: Track): MediaItem {
        val file = track.file ?: return MediaItem.EMPTY
        val md = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId("${TRACK_PREFIX}${track.id}")
            .setUri(android.net.Uri.fromFile(file))
            .setMediaMetadata(md)
            .build()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult
                .DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().build()
            val playerCommands = MediaSession.ConnectionResult
                .DEFAULT_PLAYER_COMMANDS.buildUpon().build()
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
                        .setTitle("Ace Player Ultra Instinct")
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
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
            CatalogRepository.ensureLoaded(applicationContext)
            when {
                parentId == ROOT_ID -> {
                    val items = CatalogRepository.tabOrder
                        .filter { it != "queue" }
                        .mapNotNull { CatalogRepository.getPlaylist(it) }
                        .filter { it.tracks.any { t -> t.isLocal } }
                        .map { pl ->
                            MediaItem.Builder()
                                .setMediaId("${PLAYLIST_PREFIX}${pl.key}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(pl.label)
                                        .setIsPlayable(true)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                        .build()
                                )
                                .build()
                        }
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    )
                }
                parentId.startsWith(PLAYLIST_PREFIX) -> {
                    val key = parentId.removePrefix(PLAYLIST_PREFIX)
                    val pl = CatalogRepository.getPlaylist(key)
                    val items = pl?.tracks?.filter { it.isLocal }?.map { t ->
                        MediaItem.Builder()
                            .setMediaId("${TRACK_PREFIX}${t.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(t.title)
                                    .setArtist(t.artist)
                                    .setIsPlayable(true)
                                    .setIsBrowsable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .build()
                            )
                            .build()
                    } ?: emptyList()
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    )
                }
                else -> return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.of(), params)
                )
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            CatalogRepository.ensureLoaded(applicationContext)
            if (mediaId == ROOT_ID) {
                return onGetLibraryRoot(session, browser, null)
            }
            if (mediaId.startsWith(PLAYLIST_PREFIX)) {
                val key = mediaId.removePrefix(PLAYLIST_PREFIX)
                val pl = CatalogRepository.getPlaylist(key)
                    ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                val item = MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(pl.label)
                            .setIsPlayable(true)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(item, null))
            }
            if (mediaId.startsWith(TRACK_PREFIX)) {
                val id = mediaId.removePrefix(TRACK_PREFIX)
                for (pl in CatalogRepository.playlists.values) {
                    val t = pl.tracks.firstOrNull { it.id == id && it.isLocal } ?: continue
                    return Futures.immediateFuture(LibraryResult.ofItem(buildMediaItem(t), null))
                }
            }
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            CatalogRepository.ensureLoaded(applicationContext)
            val resolved = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val id = item.mediaId
                when {
                    id.startsWith(PLAYLIST_PREFIX) -> {
                        val key = id.removePrefix(PLAYLIST_PREFIX)
                        val pl = CatalogRepository.getPlaylist(key) ?: continue
                        for (t in pl.tracks) if (t.isLocal) resolved.add(buildMediaItem(t))
                    }
                    id.startsWith(TRACK_PREFIX) -> {
                        val tid = id.removePrefix(TRACK_PREFIX)
                        for (pl in CatalogRepository.playlists.values) {
                            val t = pl.tracks.firstOrNull { it.id == tid && it.isLocal } ?: continue
                            resolved.add(buildMediaItem(t))
                            break
                        }
                    }
                    else -> {
                        // If item has a URI already, keep it
                        if (item.localConfiguration != null) resolved.add(item)
                    }
                }
            }
            return Futures.immediateFuture(resolved)
        }
    }
}
