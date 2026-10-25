package com.santy.grabacion.main

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.santy.grabacion.R

/** Especímenes del inventario como lista (tarjetas) o como galería (cuadros con la foto grande). */
class SpecimenAdapter(private val onClick: (Specimen) -> Unit) :
    ListAdapter<Specimen, SpecimenAdapter.Holder>(Diff) {

    var grid = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val code: TextView = view.findViewById(R.id.code)
        val groupRule: View = view.findViewById(R.id.groupRule)
        val group: TextView? = view.findViewById(R.id.group)
        val date: TextView = view.findViewById(R.id.date)
        val scientific: TextView = view.findViewById(R.id.scientificName)
        val commonName: TextView = view.findViewById(R.id.commonName)
        val locality: TextView? = view.findViewById(R.id.locality)
        val locationIcon: View? = view.findViewById(R.id.locationIcon)
    }

    override fun getItemViewType(position: Int) = if (grid) VIEW_GRID else VIEW_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == VIEW_GRID) R.layout.item_specimen_grid else R.layout.item_specimen
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
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
        holder.groupRule.setBackgroundColor(GroupColors.colorFor(specimen.group))
        holder.group?.text = specimen.group
        holder.date.text = specimen.collectedOn
        holder.scientific.text = specimen.scientificName
        holder.commonName.text = specimen.commonName
        holder.commonName.visibility = if (specimen.commonName.isBlank()) View.GONE else View.VISIBLE
        holder.locality?.text = specimen.locality.ifBlank { context.getString(R.string.no_locality) }
        // El icono de ubicación solo aparece si el registro tiene coordenadas GPS.
        holder.locationIcon?.visibility = if (specimen.hasLocation) View.VISIBLE else View.GONE
        holder.itemView.contentDescription = listOf(
            specimen.catalogCode, specimen.scientificName, specimen.commonName, specimen.group,
            specimen.locality, specimen.collectedOn,
        ).filter { it.isNotBlank() }.joinToString(", ")
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

    private companion object {
        const val VIEW_LIST = 0
        const val VIEW_GRID = 1
    }
}
