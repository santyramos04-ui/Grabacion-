package com.santy.grabacion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.launch
import java.io.File

/** Edición rápida: recortar, quitar audio y rotar. Siempre guarda una copia nueva. */
class EditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "uri"

        fun intent(context: Context, uri: Uri) =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_URI, uri)
    }

    private lateinit var uri: Uri
    private lateinit var trimSlider: RangeSlider
    private lateinit var trimLabel: TextView
    private lateinit var rotationLabel: TextView
    private lateinit var muteSwitch: MaterialSwitch
    private var player: ExoPlayer? = null
    private var rotation = 0
    private val exporter by lazy { VideoExporter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        findViewById<android.view.View>(R.id.root).applySystemBarPadding()
        uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java) ?: return finish()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.editor_title)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }

        trimSlider = findViewById(R.id.trimSlider)
        trimLabel = findViewById(R.id.trimLabel)
        rotationLabel = findViewById(R.id.rotationLabel)
        muteSwitch = findViewById(R.id.muteSwitch)
        trimSlider.isEnabled = false
        trimSlider.setLabelFormatter { formatDuration((it * 1000).toLong()) }
        trimSlider.addOnChangeListener { slider, _, fromUser ->
            updateTrimLabel()
            if (fromUser) player?.seekTo((slider.values[0] * 1000).toLong())
        }

        findViewById<Button>(R.id.rotateButton).setOnClickListener {
            rotation = (rotation + 90) % 360
            updateRotationLabel()
        }
        updateRotationLabel()
        findViewById<Button>(R.id.saveButton).setOnClickListener { export() }

        player = ExoPlayer.Builder(this).build().also { exo ->
            findViewById<PlayerView>(R.id.playerView).player = exo
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && !trimSlider.isEnabled) setupTrim(exo.duration)
                }
            })
            exo.prepare()
        }
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        exporter.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun setupTrim(durationMs: Long) {
        if (durationMs <= 0) return
        val seconds = durationMs / 1000f
        trimSlider.valueFrom = 0f
        trimSlider.valueTo = seconds
        trimSlider.values = listOf(0f, seconds)
        trimSlider.isEnabled = true
        updateTrimLabel()
    }

    private fun updateTrimLabel() {
        val (start, end) = trimSlider.values.let { it[0] to it[1] }
        trimLabel.text = "${getString(R.string.trim)}: ${formatDuration((start * 1000).toLong())} – " +
            formatDuration((end * 1000).toLong())
    }

    private fun updateRotationLabel() {
        rotationLabel.text = getString(R.string.rotation_value, rotation)
    }

    private fun export() {
        player?.pause()
        val values = trimSlider.values
        val startMs = if (trimSlider.isEnabled) (values[0] * 1000).toLong() else 0L
        val endMs = if (trimSlider.isEnabled) (values[1] * 1000).toLong() else Long.MIN_VALUE
        val progress = ProgressDialog(this, getString(R.string.exporting, 0)) { exporter.cancel() }
        exporter.exportEdit(
            uri,
            startMs,
            if (endMs == Long.MIN_VALUE) androidx.media3.common.C.TIME_END_OF_SOURCE else endMs,
            muteSwitch.isChecked,
            rotation,
            onProgress = {
                progress.setTitle(getString(R.string.exporting, it))
                progress.setProgress(it)
            },
            onDone = { result -> onExported(result, progress) }
        )
    }

    private fun onExported(result: Result<File>, progress: ProgressDialog) {
        result.fold(
            onSuccess = { file ->
                lifecycleScope.launch {
                    try {
                        val newUri = MediaRepository.saveVideo(this@EditorActivity, file, "EDIT_${fileTimestamp()}.mp4")
                        progress.dismiss()
                        Toast.makeText(this@EditorActivity, R.string.export_done, Toast.LENGTH_LONG).show()
                        startActivity(PlayerActivity.intent(this@EditorActivity, newUri, getString(R.string.editor_title)))
                        finish()
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
