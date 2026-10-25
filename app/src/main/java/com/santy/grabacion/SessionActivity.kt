package com.santy.grabacion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

/** Fotos de un timelapse, con opción de convertirlas en video. */
class SessionActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, path: String, title: String) =
            Intent(context, SessionActivity::class.java).putExtra(EXTRA_PATH, path).putExtra(EXTRA_TITLE, title)
    }

    private lateinit var path: String
    private var photos: List<PhotoInfo> = emptyList()
    private val adapter = MediaAdapter { position ->
        photos.getOrNull(position)?.let { startActivity(PhotoActivity.intent(this, it.uri, it.name)) }
    }
    private val deleter = MediaDeleter(this) { finish() }
    private val exporter by lazy { VideoExporter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableVaultEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session)
        findViewById<android.view.View>(R.id.root).applySystemBarPadding()
        path = intent.getStringExtra(EXTRA_PATH) ?: return finish()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = intent.getStringExtra(EXTRA_TITLE)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.session_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_make_video -> askFrameRate()
                R.id.action_share -> MediaRepository.share(this, photos.map { it.uri }, "image/jpeg")
                R.id.action_delete -> deleter.delete(
                    photos.map { it.uri }, getString(R.string.confirm_delete_session, photos.size)
                )
            }
            true
        }

        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            photos = MediaRepository.sessionPhotos(this@SessionActivity, path)
            if (photos.isEmpty()) {
                finish()
                return@launch
            }
            adapter.submitList(photos.map { GridEntry(it.uri, null, isVideo = false) })
        }
    }

    override fun onDestroy() {
        exporter.cancel()
        super.onDestroy()
    }

    private fun askFrameRate() {
        if (photos.isEmpty()) return
        val padding = (24 * resources.displayMetrics.density).toInt()
        val label = TextView(this)
        val summary = TextView(this)
        val slider = Slider(this).apply {
            valueFrom = 5f
            valueTo = 60f
            stepSize = 1f
            value = 24f
        }
        fun refresh() {
            val fps = slider.value.toInt()
            label.text = getString(R.string.fps_label, fps)
            summary.text = getString(R.string.make_video_summary, photos.size, photos.size / fps.toFloat())
        }
        slider.addOnChangeListener { _, _, _ -> refresh() }
        refresh()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(label)
            addView(slider)
            addView(summary)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.make_video_title)
            .setView(content)
            .setPositiveButton(R.string.create) { _, _ -> makeVideo(slider.value.toInt()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun makeVideo(fps: Int) {
        val progress = ProgressDialog(this, getString(R.string.exporting, 0)) { exporter.cancel() }
        exporter.exportTimelapse(
            photos.map { it.uri },
            fps,
            onProgress = {
                progress.setTitle(getString(R.string.exporting, it))
                progress.setProgress(it)
            },
            onDone = { result -> onExported(result, progress) }
        )
    }

    private fun onExported(result: Result<java.io.File>, progress: ProgressDialog) {
        result.fold(
            onSuccess = { file ->
                lifecycleScope.launch {
                    try {
                        val uri = MediaRepository.saveVideo(this@SessionActivity, file, "TL_${fileTimestamp()}.mp4")
                        progress.dismiss()
                        Toast.makeText(this@SessionActivity, R.string.export_done, Toast.LENGTH_LONG).show()
                        startActivity(PlayerActivity.intent(this@SessionActivity, uri, getString(R.string.make_video_title)))
                    } catch (e: Exception) {
                        progress.dismiss()
                        showError(e)
                    }
                }
            },
            onFailure = {
                progress.dismiss()
                showError(it)
            }
        )
    }

    private fun showError(e: Throwable) =
        Toast.makeText(this, getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
}
