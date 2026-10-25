package com.santy.grabacion.main

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import com.santy.grabacion.R

/**
 * Lista desplegable de sugerencias de iNaturalist. Los resultados ya llegan filtrados por la
 * API, así que el filtro local no descarta nada.
 */
class SuggestionAdapter(private val context: Context) : BaseAdapter(), Filterable {

    private var items: List<INaturalist.Suggestion> = emptyList()

    fun submit(suggestions: List<INaturalist.Suggestion>) {
        items = suggestions
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int): INaturalist.Suggestion = items[position]
    override fun getItemId(position: Int) = items[position].taxonId

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_suggestion, parent, false)
        val item = items[position]
        view.findViewById<TextView>(R.id.commonName).text = item.commonName.ifBlank { item.scientificName }
        view.findViewById<TextView>(R.id.scientificName).text = item.scientificName
        view.findViewById<TextView>(R.id.detail).text = listOfNotNull(
            item.iconicGroup.takeIf { it.isNotBlank() },
            item.nearbyObservations?.let { context.getString(R.string.nearby_observations, INaturalist.format(it)) }
                ?: context.getString(R.string.global_result),
        ).joinToString(", ")
        return view
    }

    override fun getFilter() = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
            values = items
            count = items.size
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? INaturalist.Suggestion)?.let { it.commonName.ifBlank { it.scientificName } } ?: ""
    }
}
