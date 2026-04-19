package com.aceburns.ultrainstinct.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Loads catalog.json from assets and cross-references with local .opus files in
 * getExternalFilesDir(null)/Music/ to build the final playable catalog.
 *
 * Primary match: YouTube id found in brackets, e.g. "[abc123XYZ-_].opus"
 * Fallback 1:   normalized title match ("01 - Black Berry Whine.opus" -> "black berry whine")
 *                against the catalog track title.
 * Fallback 2:   exact filename field from catalog equals file name.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val filename: String,
    val file: File?
) {
    val isLocal: Boolean get() = file != null && file.exists()
}

data class Playlist(
    val key: String,
    val label: String,
    val tracks: List<Track>
)

object CatalogRepository {
    private var loaded = false
    var tabOrder: List<String> = emptyList()
        private set
    var tabs: Map<String, String> = emptyMap()
        private set
    var playlists: Map<String, Playlist> = emptyMap()
        private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loadFrom(context)
        loaded = true
    }

    fun reload(context: Context) {
        loaded = false
        ensureLoaded(context)
    }

    private fun normalizeTitle(raw: String): String {
        var s = raw.lowercase()
        // Strip file extension
        s = s.removeSuffix(".opus")
        // Strip leading track numbers like "01 - " or "01. "
        s = s.replace(Regex("^\\s*\\d{1,3}\\s*[-.\\s]+\\s*"), "")
        // Strip trailing [id].opus markers and "(ft ...)" sections
        s = s.replace(Regex("\\s*\\[[A-Za-z0-9_\\-]{3,}\\]"), "")
        s = s.replace(Regex("\\s*\\(ft\\.?[^)]*\\)"), "")
        s = s.replace(Regex("\\s*ft\\.?\\s+.*$"), "")
        // Strip anything after " - " (sometimes artist prefixes are present)
        // Only strip if we have a clear artist - title form
        // e.g. "Ace Burns - Black Berry Whine" -> keep only title portion on the right
        val parts = s.split(" - ")
        if (parts.size >= 2) {
            // take last part (usually title)
            s = parts.last()
        }
        // Remove non-alphanumeric
        s = s.replace(Regex("[^a-z0-9]+"), "")
        return s
    }

    private fun loadFrom(context: Context) {
        val musicDir = File(context.getExternalFilesDir(null), "Music").apply { mkdirs() }
        android.util.Log.i("CatalogRepo", "musicDir=${musicDir.absolutePath} exists=${musicDir.exists()} canRead=${musicDir.canRead()}")
        val listed = musicDir.listFiles()
        android.util.Log.i("CatalogRepo", "listFiles() returned ${listed?.size ?: -1} entries")

        // Build maps
        val idToFile = mutableMapOf<String, File>()
        val titleKeyToFile = mutableMapOf<String, File>()
        val nameToFile = mutableMapOf<String, File>()
        val idRegex = Regex("""\[([A-Za-z0-9_\-]{6,})\]\.opus$""")
        listed?.forEach { f ->
            if (f.isFile && f.name.endsWith(".opus")) {
                nameToFile[f.name] = f
                val m = idRegex.find(f.name)
                if (m != null) {
                    idToFile[m.groupValues[1]] = f
                }
                val key = normalizeTitle(f.name)
                if (key.isNotEmpty()) titleKeyToFile.putIfAbsent(key, f)
            }
        }
        android.util.Log.i("CatalogRepo", "maps built: name=${nameToFile.size} id=${idToFile.size} title=${titleKeyToFile.size}")

        // Load catalog.json
        val raw = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)

        val tabsJson = root.getJSONObject("tabs")
        val tabsMap = mutableMapOf<String, String>()
        val tabKeys = tabsJson.keys()
        while (tabKeys.hasNext()) {
            val k = tabKeys.next()
            tabsMap[k] = tabsJson.getString(k)
        }
        tabs = tabsMap

        val orderArr = root.getJSONArray("tab_order")
        val orderList = mutableListOf<String>()
        for (i in 0 until orderArr.length()) orderList.add(orderArr.getString(i))
        tabOrder = orderList

        val plJson = root.getJSONObject("playlists")
        val plMap = mutableMapOf<String, Playlist>()
        val plKeys = plJson.keys()
        while (plKeys.hasNext()) {
            val key = plKeys.next()
            val arr = plJson.getJSONArray(key)
            val tracks = mutableListOf<Track>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val title = o.getString("title")
                val artist = o.getString("artist")
                val filename = o.optString("filename", "$artist - $title [$id].opus")

                // Match strategy: id -> filename -> normalized title
                var file: File? = idToFile[id]
                if (file == null) file = nameToFile[filename]
                if (file == null) {
                    val titleKey = normalizeTitle(title)
                    if (titleKey.isNotEmpty()) file = titleKeyToFile[titleKey]
                }
                tracks.add(Track(id, title, artist, filename, file))
            }
            val label = tabsMap[key] ?: key
            plMap[key] = Playlist(key, label, tracks)
        }

        // Build a "queue" playlist dynamically — start empty, label from tabs
        if (!plMap.containsKey("queue")) {
            plMap["queue"] = Playlist("queue", tabsMap["queue"] ?: "Queue", emptyList())
        }

        playlists = plMap
    }

    fun getPlaylist(key: String): Playlist? = playlists[key]

    /** Build the ordered list of displayable tabs (skip queue if empty). */
    fun displayTabOrder(): List<String> {
        return tabOrder.filter { it == "queue" || (playlists[it]?.tracks?.any { t -> t.isLocal } == true) || (playlists[it]?.tracks?.isNotEmpty() == true) }
    }
}
