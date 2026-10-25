package com.santy.grabacion.collection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

/** Estadísticas de la colección: riqueza, diversidad, composición, tiempo, altitud y geografía. */
class StatsFragment : CollectionTab(R.layout.fragment_stats) {

    private lateinit var content: LinearLayout
    private val exporter = ExportController(this, { SpecimenRepository.all(requireContext()) })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        content = view.findViewById(R.id.content)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.stats_menu)
        toolbar.setOnMenuItemClickListener { exporter.show(); true }
    }

    override fun reload() {
        if (view == null) return
        viewLifecycleOwner.lifecycleScope.launch { render(CollectionStats(SpecimenRepository.all(requireContext()))) }
    }

    private fun render(stats: CollectionStats) {
        content.removeAllViews()
        if (stats.total == 0) {
            card(getString(R.string.stats_title), getString(R.string.stats_empty))
            return
        }

        card(getString(R.string.stats_summary)).metrics(
            stats.total.toString() to getString(R.string.metric_records),
            stats.species.toString() to getString(R.string.metric_species),
            stats.groups.toString() to getString(R.string.metric_groups),
            stats.localities.toString() to getString(R.string.metric_localities),
            stats.collectors.toString() to getString(R.string.metric_collectors),
            "${stats.percent(stats.withLocation)} %" to getString(R.string.metric_with_gps),
            "${stats.percent(stats.withPhoto)} %" to getString(R.string.metric_with_photo),
        )

        val diversity = card(getString(R.string.stats_diversity), getString(R.string.stats_diversity_help))
        diversity.metrics(
            fmt(stats.chao1, 1) to getString(R.string.metric_chao1),
            (stats.completeness?.let { "${Math.round(it * 100)} %" } ?: "—") to getString(R.string.metric_completeness),
            fmt(stats.shannon, 2) to getString(R.string.metric_shannon),
            fmt(stats.simpson, 2) to getString(R.string.metric_simpson),
            (stats.pielou?.let { fmt(it, 2) } ?: "—") to getString(R.string.metric_pielou),
            "${stats.singletons} / ${stats.doubletons}" to getString(R.string.metric_singletons),
        )

        val curve = stats.accumulationCurve()
        val completeness = stats.completeness ?: 0.0
        card(
            getString(R.string.stats_accumulation),
            getString(if (completeness >= 0.9) R.string.stats_accumulation_stable else R.string.stats_accumulation_rising)
        ).addView(LineChartView(requireContext()).apply {
            setData(curve, stats.chao1.takeIf { stats.species > 0 })
            contentDescription = getString(R.string.chart_accumulation_summary, stats.species, stats.total, fmt(stats.chao1, 1))
        })

        card(getString(R.string.stats_by_group)).addView(BarListView(requireContext()).apply {
            setBars(stats.byGroup().map {
                BarListView.Bar(it.label, it.value, GroupColors.colorFor(if (it.label == "Sin grupo") "" else it.label))
            }, stats.total)
        })

        card(getString(R.string.stats_top_species)).addView(BarListView(requireContext()).apply {
            setBars(stats.topSpecies().map { BarListView.Bar(it.label, it.value, italic = true) }, stats.total)
        })

        val months = stats.byMonth()
        if (months.isNotEmpty()) {
            val parse = SimpleDateFormat("yyyy-MM", Locale.US)
            val label = SimpleDateFormat("MMM yy", Locale.getDefault())
            val labels = months.map { m -> runCatching { label.format(parse.parse(m.label)!!) }.getOrDefault(m.label) }
            val busiest = months.indices.maxBy { months[it].value }
            card(getString(R.string.stats_by_month)).addView(ColumnChartView(requireContext()).apply {
                setData(months.map { it.value }, labels)
                contentDescription = getString(R.string.chart_month_summary, months.size, labels[busiest], months[busiest].value)
            })
        }

        stats.altitude()?.let { alt ->
            val altitudeCard = card(getString(R.string.stats_altitude), getString(R.string.stats_altitude_help, alt.count))
            altitudeCard.metrics(
                meters(alt.min) to getString(R.string.metric_min),
                meters(alt.median) to getString(R.string.metric_median),
                meters(alt.max) to getString(R.string.metric_max),
            )
            altitudeCard.addView(BarListView(requireContext()).apply {
                setBars(stats.altitudeBands().map { BarListView.Bar(it.label, it.value) }, alt.count)
            })
        }

        stats.geo()?.let { geo ->
            val geoCard = card(getString(R.string.stats_geography), getString(R.string.stats_geography_help))
            geoCard.metrics(
                geo.points.toString() to getString(R.string.metric_points),
                "${fmt(geo.maxDistanceKm, 1)} km" to getString(R.string.metric_max_distance),
                "${fmt(geo.eooKm2, 1)} km²" to getString(R.string.metric_eoo),
            )
            geoCard.addView(TextView(requireContext()).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                text = getString(
                    R.string.stats_geography_detail,
                    coord(geo.centroidLat), coord(geo.centroidLng),
                    coord(geo.minLat), coord(geo.maxLat), coord(geo.minLng), coord(geo.maxLng),
                )
            })
        }

        val localities = stats.topLocalities()
        if (localities.isNotEmpty()) {
            card(getString(R.string.stats_localities)).addView(BarListView(requireContext()).apply {
                setBars(localities.map { BarListView.Bar(it.label, it.value) }, stats.total)
            })
        }
    }

    /** Añade una tarjeta y devuelve su contenedor. */
    private fun card(title: String, subtitle: String? = null): LinearLayout {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.item_stat_card, content, false)
        view.findViewById<TextView>(R.id.title).text = title
        view.findViewById<TextView>(R.id.subtitle).apply {
            text = subtitle
            visibility = if (subtitle == null) View.GONE else View.VISIBLE
        }
        content.addView(view)
        return view.findViewById(R.id.body)
    }

    /** Cifras en filas de tres. */
    private fun LinearLayout.metrics(vararg items: Pair<String, String>) {
        items.toList().chunked(3).forEach { row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (value, label) ->
                val metric = LayoutInflater.from(context).inflate(R.layout.item_metric, line, false)
                metric.findViewById<TextView>(R.id.value).text = value
                metric.findViewById<TextView>(R.id.label).text = label
                line.addView(metric)
            }
            repeat(3 - row.size) {
                line.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            }
            addView(line)
        }
    }

    private fun fmt(value: Double, decimals: Int) = String.format(Locale.getDefault(), "%.${decimals}f", value)

    private fun meters(value: Double) = String.format(Locale.getDefault(), "%,.0f m", value)

    private fun coord(value: Double) = String.format(Locale.US, "%.4f", value)
}
