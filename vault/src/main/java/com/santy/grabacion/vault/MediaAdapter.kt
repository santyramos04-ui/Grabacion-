package com.santy.grabacion.vault

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

data class GridEntry(val uri: Uri, val label: String?, val isVideo: Boolean)

class MediaAdapter(private val onClick: (position: Int) -> Unit) :
    ListAdapter<GridEntry, MediaAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val play: View = view.findViewById(R.id.playIcon)
        val label: TextView = view.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
        return Holder(view).also { holder ->
            view.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(position)
            }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        Glide.with(holder.thumb).load(entry.uri).centerCrop().into(holder.thumb)
        holder.play.visibility = if (entry.isVideo) View.VISIBLE else View.GONE
        holder.label.visibility = if (entry.label == null) View.GONE else View.VISIBLE
        holder.label.text = entry.label
    }

    private object Diff : DiffUtil.ItemCallback<GridEntry>() {
        override fun areItemsTheSame(a: GridEntry, b: GridEntry) = a.uri == b.uri
        override fun areContentsTheSame(a: GridEntry, b: GridEntry) = a == b
    }
}
