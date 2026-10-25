package com.santy.grabacion.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.santy.grabacion.R

class SpecimenAdapter(private val onClick: (Specimen) -> Unit) :
    ListAdapter<Specimen, SpecimenAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val scientific: TextView = view.findViewById(R.id.scientificName)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val date: TextView = view.findViewById(R.id.date)
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
        holder.scientific.text = specimen.scientificName
        holder.subtitle.text = listOf(specimen.commonName, specimen.group).filter { it.isNotBlank() }.joinToString(" · ")
        holder.date.text = specimen.collectedOn
    }

    private object Diff : DiffUtil.ItemCallback<Specimen>() {
        override fun areItemsTheSame(a: Specimen, b: Specimen) = a.id == b.id
        override fun areContentsTheSame(a: Specimen, b: Specimen) = a == b
    }
}
