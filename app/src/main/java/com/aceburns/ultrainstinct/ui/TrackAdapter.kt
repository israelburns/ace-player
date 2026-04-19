package com.aceburns.ultrainstinct.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aceburns.ultrainstinct.R
import com.aceburns.ultrainstinct.data.Track

class TrackAdapter(
    private var tracks: List<Track> = emptyList(),
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private var activeTrackId: String? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.trackNum)
        val title: TextView = v.findViewById(R.id.trackTitle)
        val artist: TextView = v.findViewById(R.id.trackArtist)
    }

    fun setTracks(newTracks: List<Track>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    fun setActiveTrackId(id: String?) {
        if (activeTrackId == id) return
        activeTrackId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = tracks[position]
        holder.number.text = (position + 1).toString()
        holder.title.text = t.title
        holder.artist.text = if (t.isLocal) t.artist else "${t.artist} (missing)"

        val active = activeTrackId != null && activeTrackId == t.id
        holder.itemView.isSelected = active

        when {
            !t.isLocal -> {
                holder.title.setTextColor(Color.parseColor("#555555"))
                holder.title.alpha = 0.65f
                holder.artist.alpha = 0.65f
                holder.number.alpha = 0.65f
            }
            active -> {
                holder.title.setTextColor(Color.parseColor("#c8a54e"))
                holder.title.alpha = 1.0f
                holder.artist.alpha = 1.0f
                holder.number.alpha = 1.0f
            }
            else -> {
                holder.title.setTextColor(Color.parseColor("#ffffff"))
                holder.title.alpha = 1.0f
                holder.artist.alpha = 1.0f
                holder.number.alpha = 1.0f
            }
        }

        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = tracks.size
}
