package com.aceburns.ultrainstinct.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aceburns.ultrainstinct.R
import com.aceburns.ultrainstinct.data.CatalogRepository
import com.aceburns.ultrainstinct.data.Track
import com.aceburns.ultrainstinct.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerHostActivity : AppCompatActivity() {

    private lateinit var tabBar: LinearLayout
    private lateinit var trackList: RecyclerView
    private lateinit var adapter: TrackAdapter
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var txtPos: TextView
    private lateinit var txtDur: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var posJob: Job? = null
    private var userSeeking = false

    private var currentTabKey: String = ""
    private var currentTracks: List<Track> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_host)

        tabBar = findViewById(R.id.tabBar)
        trackList = findViewById(R.id.trackList)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        seekBar = findViewById(R.id.seekBar)
        txtPos = findViewById(R.id.txtPos)
        txtDur = findViewById(R.id.txtDur)
        nowTitle = findViewById(R.id.nowTitle)
        nowArtist = findViewById(R.id.nowArtist)

        // Bind-only: the MediaController.buildAsync below will bind to the
        // MediaLibraryService. We intentionally do NOT call startForegroundService
        // here — MediaLibraryService promotes itself to foreground automatically
        // when playback starts. Calling startForegroundService without then calling
        // startForeground within 5s crashes on Android 12+.
        CatalogRepository.ensureLoaded(applicationContext)

        adapter = TrackAdapter(emptyList()) { pos -> onTrackClicked(pos) }
        trackList.layoutManager = LinearLayoutManager(this)
        trackList.adapter = adapter

        buildTabs()

        btnPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        btnPrev.setOnClickListener { controller?.seekToPrevious() }
        btnNext.setOnClickListener { controller?.seekToNext() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = controller?.duration ?: 0L
                    if (dur > 0) txtPos.text = formatMs((progress / 1000.0 * dur).toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val c = controller ?: return
                val dur = c.duration
                if (dur > 0) c.seekTo(((sb?.progress ?: 0) / 1000.0 * dur).toLong())
            }
        })

        // Connect MediaController asynchronously
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { f ->
            f.addListener({
                try {
                    controller = f.get()
                    controller?.addListener(playerListener)
                    refreshNowPlaying()
                    startPositionUpdates()
                } catch (_: Exception) {}
            }, MoreExecutors.directExecutor())
        }

        // Select first tab by default
        val first = CatalogRepository.tabOrder.firstOrNull { it != "queue" } ?: CatalogRepository.tabOrder.firstOrNull()
        if (first != null) selectTab(first)
    }

    private fun buildTabs() {
        tabBar.removeAllViews()
        for (key in CatalogRepository.tabOrder) {
            if (key == "queue") continue // skip queue for this UI; AA still gets it from catalog if added later
            val pl = CatalogRepository.getPlaylist(key) ?: continue
            if (pl.tracks.isEmpty()) continue
            val tv = TextView(this)
            tv.text = pl.label
            tv.setPadding(dp(14), dp(8), dp(14), dp(8))
            tv.textSize = 12f
            tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tv.setBackgroundResource(R.drawable.bg_tab)
            // Default = inactive gray
            tv.setTextColor(0xFF666666.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(3), dp(2), dp(3), dp(2))
            tv.layoutParams = lp
            tv.minHeight = dp(38)
            tv.gravity = android.view.Gravity.CENTER
            tv.isClickable = true
            tv.tag = key
            tv.setOnClickListener { selectTab(key) }
            tabBar.addView(tv)
        }
    }

    private fun selectTab(key: String) {
        currentTabKey = key
        val pl = CatalogRepository.getPlaylist(key) ?: return
        currentTracks = pl.tracks
        adapter.setTracks(currentTracks)
        // Update tab selection visuals (active = gold #c8a54e, inactive = gray #666)
        for (i in 0 until tabBar.childCount) {
            val child = tabBar.getChildAt(i)
            val childKey = child.tag as? String
            val sel = childKey == key
            child.isSelected = sel
            (child as? TextView)?.setTextColor(
                if (sel) 0xFFC8A54E.toInt() else 0xFF666666.toInt()
            )
        }
    }

    private fun onTrackClicked(position: Int) {
        val c = controller
        if (c == null) {
            android.widget.Toast.makeText(this, "Connecting…", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.w("PlayerHost", "Track tap before MediaController connected (pos=$position)")
            return
        }
        if (position < 0 || position >= currentTracks.size) return
        val clicked = currentTracks[position]
        if (!clicked.isLocal) {
            android.widget.Toast.makeText(this, "File missing: ${clicked.title}", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.w("PlayerHost", "Tapped non-local track: id=${clicked.id} title=${clicked.title} file=${clicked.filename}")
            return
        }
        val playable = currentTracks.filter { it.isLocal }
        val targetIndex = playable.indexOfFirst { it.id == clicked.id }.coerceAtLeast(0)
        val items = playable.map { t ->
            MediaItem.Builder()
                .setMediaId("track:${t.id}")
                .setUri(android.net.Uri.fromFile(t.file))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
        }
        if (items.isEmpty()) {
            android.widget.Toast.makeText(this, "No playable tracks in this tab", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.util.Log.i("PlayerHost", "Play: ${clicked.title} uri=${clicked.file?.absolutePath} idx=$targetIndex of ${items.size}")
        c.setMediaItems(items, targetIndex, 0L)
        c.prepare()
        c.play()
        adapter.setActiveTrackId(clicked.id)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            refreshNowPlaying()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshNowPlaying()
        }
    }

    private fun refreshNowPlaying() {
        val c = controller ?: return
        val md = c.mediaMetadata
        nowTitle.text = (md.title ?: "ACE PLAYER").toString()
        nowArtist.text = (md.artist ?: "Tap a track to start").toString()
        btnPlayPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val curId = c.currentMediaItem?.mediaId?.removePrefix("track:")
        adapter.setActiveTrackId(curId)
    }

    private fun startPositionUpdates() {
        posJob?.cancel()
        posJob = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null) {
                    val dur = c.duration.coerceAtLeast(0L)
                    val pos = c.currentPosition.coerceAtLeast(0L)
                    txtDur.text = formatMs(dur)
                    txtPos.text = formatMs(pos)
                    if (!userSeeking && dur > 0) {
                        seekBar.progress = ((pos.toDouble() / dur) * 1000).toInt()
                    }
                }
                delay(500L)
            }
        }
    }

    private fun formatMs(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%d:%02d", m, s)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        posJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}
