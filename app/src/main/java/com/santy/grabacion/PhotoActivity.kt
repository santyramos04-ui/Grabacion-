package com.santy.grabacion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar

class PhotoActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, uri: Uri, name: String) =
            Intent(context, PhotoActivity::class.java).putExtra(EXTRA_URI, uri).putExtra(EXTRA_NAME, name)
    }

    private val deleter = MediaDeleter(this) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo)
        findViewById<android.view.View>(R.id.root).applySystemBarPadding()
        val uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java) ?: return finish()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = intent.getStringExtra(EXTRA_NAME)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.photo_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share -> MediaRepository.share(this, listOf(uri), "image/jpeg")
                R.id.action_delete -> deleter.delete(listOf(uri), getString(R.string.confirm_delete))
            }
            true
        }

        Glide.with(this).load(uri).into(findViewById<ImageView>(R.id.image))
    }
}
