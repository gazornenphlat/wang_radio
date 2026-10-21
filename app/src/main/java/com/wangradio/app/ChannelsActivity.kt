package com.wangradio.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wangradio.app.databinding.ActivityChannelsBinding
import com.wangradio.app.databinding.RowChannelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/** Main screen: the stations (one per music folder) available on the server. Tap one to listen. */
class ChannelsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_CHANNEL_NAME = "channel_name"
    }

    private lateinit var b: ActivityChannelsBinding
    private lateinit var api: StationApi
    private val art = android.util.LruCache<String, Bitmap>(24)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(b.root)
        api = StationApi(this)
        handleSetupLink(intent)
        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    load()
                    delay(10_000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSetupLink(intent)
        lifecycleScope.launch { load() }
    }

    /** wangradio://setup?url=https://xxx.trycloudflare.com&key=secret (from the station's phone notification) */
    private fun handleSetupLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "wangradio" || data.host != "setup") return
        val url = data.getQueryParameter("url") ?: return
        Prefs.save(this, url, data.getQueryParameter("key") ?: "")
        Toast.makeText(this, getString(R.string.setup_saved, url), Toast.LENGTH_LONG).show()
    }

    private suspend fun load() {
        if (!Prefs.isConfigured(this)) {
            showMessage(getString(R.string.not_configured_main))
            return
        }
        val list = api.channels()
        if (list == null) {
            showMessage(getString(R.string.offline))
            return
        }
        if (list.isEmpty()) {
            showMessage(getString(R.string.no_stations))
            return
        }
        b.message.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        b.list.removeAllViews()
        for ((i, c) in list.withIndex()) {
            val row = RowChannelBinding.inflate(inflater, b.list, false)
            val mine = c.id == Prefs.channel(this)
            row.chNumber.text = getString(R.string.station_number, roman(i + 1))
            row.chName.text = c.name
            row.chPlaying.text = c.nowPlaying
            // your current station gets the orange chip and full-colour art; the rest are two-tone
            row.chLive.text = getString(if (mine) R.string.current_station else R.string.live_listeners, c.listeners)
            row.chLive.setBackgroundResource(if (mine) R.drawable.chip_on else R.drawable.chip)
            row.chArt.colorFilter = if (mine) null
                else android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
            loadArt(row.chArt, c.artUrl)
            row.root.setOnClickListener { choose(c) }
            b.list.addView(row.root)
        }
    }

    private fun showMessage(text: String) {
        b.list.removeAllViews()
        b.message.text = text
        b.message.visibility = View.VISIBLE
    }

    private fun choose(c: Channel) {
        Prefs.setChannel(this, c.id, c.name)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_CHANNEL, c.id)
                .putExtra(EXTRA_CHANNEL_NAME, c.name)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun loadArt(view: ImageView, url: String?) {
        view.tag = url
        if (url == null) return
        art.get(url)?.let { return view.setImageBitmap(it) }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            if (bmp != null && view.tag == url) {
                art.put(url, bmp)
                view.setImageBitmap(bmp)
            }
        }
    }
}
