package com.santy.grabacion.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import com.santy.grabacion.core.applySystemBarPadding

class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, uri: Uri, name: String) =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_URI, uri).putExtra(EXTRA_NAME, name)
    }

    private lateinit var uri: Uri
    private var player: ExoPlayer? = null
    private val deleter = MediaDeleter(this) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableVaultEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        findViewById<android.view.View>(R.id.root).applySystemBarPadding()
        uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java) ?: return finish()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = intent.getStringExtra(EXTRA_NAME)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.video_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> startActivity(EditorActivity.intent(this, uri))
                R.id.action_share -> MediaRepository.share(this, listOf(uri), "video/mp4")
                R.id.action_delete -> deleter.delete(listOf(uri), getString(R.string.confirm_delete))
            }
            true
        }

        player = ExoPlayer.Builder(this).build().also {
            findViewById<PlayerView>(R.id.playerView).player = it
            it.setMediaItem(MediaItem.fromUri(uri))
            it.prepare()
            it.play()
        }
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
