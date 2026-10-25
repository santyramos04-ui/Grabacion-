package com.santy.grabacion.vault

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.santy.grabacion.core.formatDuration
import com.santy.grabacion.recorder.CameraService
import kotlinx.coroutines.launch

class GalleryFragment : Fragment(R.layout.fragment_gallery) {

    private lateinit var tabs: TabLayout
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var grantButton: Button
    private val adapter = MediaAdapter(::onItemClick)

    private var videos: List<VideoInfo> = emptyList()
    private var sessions: List<TimelapseSession> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tabs = view.findViewById(R.id.tabs)
        emptyView = view.findViewById(R.id.emptyView)
        emptyText = view.findViewById(R.id.emptyText)
        grantButton = view.findViewById(R.id.grantButton)

        val grid = view.findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        grid.adapter = adapter

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showCurrentTab()
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        grantButton.setOnClickListener { permissionLauncher.launch(mediaPermissions().toTypedArray()) }

        // Al terminar una grabación aparece el archivo nuevo sin tener que salir de la galería.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                CameraService.status.collect { reload() }
            }
        }
    }

    private fun reload() {
        if (view == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            videos = MediaRepository.videos(context)
            sessions = MediaRepository.sessions(context)
            showCurrentTab()
        }
    }

    private fun showCurrentTab() {
        val showingVideos = tabs.selectedTabPosition == 0
        val entries = if (showingVideos) {
            videos.map { GridEntry(it.uri, formatDuration(it.durationMs), isVideo = true) }
        } else {
            sessions.map {
                GridEntry(it.cover, "${it.title} · ${getString(R.string.session_photos, it.count)}", isVideo = false)
            }
        }
        adapter.submitList(entries)

        val missingPermission = mediaPermissions().any {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        grantButton.visibility = if (missingPermission) View.VISIBLE else View.GONE
        emptyText.setText(
            when {
                missingPermission -> R.string.media_permission
                showingVideos -> R.string.empty_videos
                else -> R.string.empty_timelapses
            }
        )
    }

    private fun onItemClick(position: Int) {
        val context = requireContext()
        if (tabs.selectedTabPosition == 0) {
            videos.getOrNull(position)?.let { startActivity(PlayerActivity.intent(context, it.uri, it.name)) }
        } else {
            sessions.getOrNull(position)?.let { startActivity(SessionActivity.intent(context, it.path, it.title)) }
        }
    }
}
