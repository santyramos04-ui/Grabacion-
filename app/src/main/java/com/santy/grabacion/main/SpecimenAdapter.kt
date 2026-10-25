package com.santy.grabacion.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.santy.grabacion.R

class SpecimenAdapter(private val onClick: (Specimen) -> Unit) :
    ListAdapter<Specimen, SpecimenAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val code: TextView = view.findViewById(R.id.code)
        val group: TextView = view.findViewById(R.id.group)
        val date: TextView = view.findViewById(R.id.date)
        val scientific: TextView = view.findViewById(R.id.scientificName)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_specimen, parent, false)
        return Holder(view).also { holder ->
            view.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(getItem(position))
            }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val specimen = getItem(position)
        val context = holder.itemView.context
        holder.code.text = specimen.catalogCode
        holder.code.background = ContextCompat.getDrawable(context, R.drawable.bg_code)?.mutate()?.apply {
            setTint(GroupColors.colorFor(specimen.group))
        }
        holder.group.text = specimen.group
        holder.date.text = specimen.collectedOn
        holder.scientific.text = specimen.scientificName
        val place = if (specimen.hasLocation) "📍 ${specimen.locality}".trimEnd() else specimen.locality
        holder.subtitle.text = listOf(specimen.commonName, place).filter { it.isNotBlank() }.joinToString(" · ")
        holder.subtitle.visibility = if (holder.subtitle.text.isEmpty()) View.GONE else View.VISIBLE
        val photo = specimen.photo
        if (photo == null) {
            Glide.with(holder.thumb).clear(holder.thumb)
            holder.thumb.setImageResource(R.drawable.ic_camera)
            holder.thumb.scaleType = ImageView.ScaleType.CENTER
        } else {
            holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(holder.thumb).load(SpecimenPhotos.file(context, photo)).into(holder.thumb)
        }
    }

    private object Diff : DiffUtil.ItemCallback<Specimen>() {
        override fun areItemsTheSame(a: Specimen, b: Specimen) = a.id == b.id
        override fun areContentsTheSame(a: Specimen, b: Specimen) = a == b
    }
}
