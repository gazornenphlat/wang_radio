package com.wangradio.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.wangradio.app.databinding.ActivityMainBinding
import com.wangradio.app.databinding.RowTrackBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var api: StationApi
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.let { runCatching { it.get() }.getOrNull() }
    private var lastArt: String? = null
    private var artJob: Job? = null
    private var rating: String? = null
    private var stars = 0
    private var currentId: String? = null
    private val starButtons by lazy { listOf(b.star1, b.star2, b.star3, b.star4, b.star5) }
    private var switchTo: String? = null          // station picked on the main screen, to start playing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        api = StationApi(this)

        b.playButton.setOnClickListener { togglePlay() }
        b.settingsButton.setOnClickListener { showSettings() }
        b.stationsButton.setOnClickListener { openStations() }
        b.headerBox.setOnClickListener { openStations() }
        starButtons.forEachIndexed { i, btn -> btn.setOnClickListener { setStars(i + 1) } }
        b.nextButton.setOnClickListener { skip("next") }
        b.nextRow.rowAction.visibility = android.view.View.VISIBLE
        b.nextRow.rowAction.setOnClickListener { skipUpNext() }
        b.prevButton.setOnClickListener { skip("back") }
        b.prevRow.rowLabel.setText(R.string.label_previous)
        b.nextRow.rowLabel.setText(R.string.label_next)
        b.prevRow.root.visibility = android.view.View.GONE
        b.nextRow.root.visibility = android.view.View.GONE
        // "previously" is the completed row: parchment, muted, struck through, artwork in greyscale
        b.prevRow.rowBody.setBackgroundColor(getColor(R.color.surface))
        b.prevRow.rowTitle.setTextColor(getColor(R.color.muted))
        b.prevRow.rowLabel.setTextColor(getColor(R.color.muted))
        b.prevRow.rowTitle.paintFlags = b.prevRow.rowTitle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        b.prevRow.rowArt.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        handleIntent(intent)
        if (!Prefs.isConfigured(this)) showSettings()

        // While the screen is visible, keep the song info / thumbs fresh even when not playing.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    api.nowPlaying()?.let { showNowPlaying(it) }
                        ?: run { if (Prefs.isConfigured(this@MainActivity)) showStatus(getString(R.string.offline)) }
                    delay(5000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        showStationName()
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS)
            showSettings()
        }
        intent?.getStringExtra(ChannelsActivity.EXTRA_CHANNEL)?.let { id ->
            intent.removeExtra(ChannelsActivity.EXTRA_CHANNEL)
            switchTo = id
            lastArt = null
            playPendingStation()
        }
    }

    private fun showStationName() {
        b.header.text = Prefs.channelName(this).ifEmpty { getString(R.string.app_name) }
    }

    private fun openStations() {
        startActivity(Intent(this, ChannelsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    /** A station was picked on the main screen: tune in to it. */
    private fun playPendingStation() {
        val id = switchTo ?: return
        val c = controller ?: return                    // tried again once the player connects
        switchTo = null
        c.setMediaItem(MediaItem.Builder().setMediaId(RadioService.CHANNEL_PREFIX + id).build())
        c.prepare()
        c.play()
        lifecycleScope.launch { api.nowPlaying()?.let { showNowPlaying(it) } }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { f ->
            f.addListener({
                controller?.addListener(playerListener)
                updatePlayButton()
                playPendingStation()
            }, MoreExecutors.directExecutor())
        }
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    // ------------------------------------------------------------------ playback
    private fun togglePlay() {
        val c = controller ?: return
        if (!Prefs.isConfigured(this)) return showSettings()
        if (c.isPlaying || c.playWhenReady) {
            c.stop()
            c.clearMediaItems()
        } else {
            c.setMediaItem(MediaItem.Builder().setMediaId(RadioService.LIVE_ID).build())
            c.prepare()
            c.play()
        }
        updatePlayButton()
    }

    private fun restartIfPlaying() {
        val c = controller ?: return
        if (c.playWhenReady) {
            c.setMediaItem(MediaItem.Builder().setMediaId(RadioService.LIVE_ID).build())
            c.prepare(); c.play()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updatePlayButton()
        override fun onMediaMetadataChanged(m: MediaMetadata) {
            m.artworkUri?.toString()?.let { loadArt(it) }
        }
    }

    private fun updatePlayButton() {
        val c = controller
        val playing = c != null && c.playWhenReady
        b.playIcon.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
        // playing: the icon box inverts to charcoal
        b.playIcon.setBackgroundColor(getColor(if (playing) R.color.border else R.color.card))
        b.playIcon.imageTintList = ColorStateList.valueOf(getColor(if (playing) R.color.card else R.color.ink))
        b.playKicker.setText(if (playing) R.string.tap_to_stop else R.string.tune_in)
        b.playLabel.setText(if (playing) R.string.on_air_label else R.string.listen_live)
        b.playButton.contentDescription = getString(if (playing) R.string.stop else R.string.play)
        when {
            c?.playbackState == Player.STATE_BUFFERING && playing -> showStatus(getString(R.string.buffering))
            playing -> showStatus(getString(R.string.on_air))
            else -> showStatus(null)
        }
    }

    private fun showStatus(text: String?) {
        b.status.text = text ?: ""
        b.status.visibility = if (text.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ------------------------------------------------------------------ now playing
    private fun showNowPlaying(np: NowPlaying) {
        b.title.text = np.title
        if (b.status.text == getString(R.string.offline)) updatePlayButton()
        b.artist.text = listOf(np.artist, np.album).filter { it.isNotEmpty() }.joinToString(" · ")
            .ifEmpty { getString(R.string.tagline) }
        currentId = np.id
        paintStars(np.stars)
        starButtons.forEach { it.isEnabled = np.id != null; it.alpha = if (np.id != null) 1f else 0.4f }
        showRow(b.prevRow, np.previous)
        showRow(b.nextRow, np.next)
        loadArt(np.artUrl)
    }

    /** Filled cells turn orange with a charcoal star; empty cells stay white with a grey star. */
    private fun paintStars(n: Int) {
        stars = n
        val on = ColorStateList.valueOf(getColor(R.color.on_primary))
        val off = ColorStateList.valueOf(getColor(R.color.muted))
        starButtons.forEachIndexed { i, btn ->
            btn.imageTintList = if (i < n) on else off
            btn.setBackgroundColor(getColor(if (i < n) R.color.primary else R.color.card))
        }
    }

    /** Tap a star to rate; tap the same star again to clear. The station sends it to Plex. */
    private fun setStars(n: Int) {
        val id = currentId ?: return Toast.makeText(this, R.string.cant_rate_break, Toast.LENGTH_SHORT).show()
        val want = if (n == stars) 0 else n
        paintStars(want)
        lifecycleScope.launch {
            api.rateStars(id, want)
                .onSuccess { saved ->
                    paintStars(saved)
                    Toast.makeText(
                        this@MainActivity,
                        when (saved) {
                            0 -> getString(R.string.stars_cleared)
                            1 -> getString(R.string.one_star_skip)
                            else -> getString(R.string.stars_saved, "★".repeat(saved))
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, getString(R.string.rating_failed, it.message ?: ""), Toast.LENGTH_SHORT).show()
                    api.nowPlaying()?.let { np -> showNowPlaying(np) }
                }
        }
    }

    /** Swap the up-next song before it plays. */
    private fun skipUpNext() {
        b.nextRow.rowAction.isEnabled = false
        lifecycleScope.launch {
            val t = api.skipUpNext()
            b.nextRow.rowAction.isEnabled = true
            if (t == null) {
                Toast.makeText(this@MainActivity, R.string.upnext_failed, Toast.LENGTH_SHORT).show()
            } else {
                showRow(b.nextRow, t)
                Toast.makeText(this@MainActivity, R.string.upnext_changed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRow(row: RowTrackBinding, t: TrackRef?) {
        if (t == null) {
            row.root.visibility = android.view.View.GONE
            return
        }
        row.root.visibility = android.view.View.VISIBLE
        row.rowTitle.text = t.title
        row.rowArtist.text = t.artist
        loadInto(row.rowArt, t.artUrl)
    }

    // Small artwork for the previous / up next rows (cached).
    private val thumbs = object : android.util.LruCache<String, Bitmap>(24) {}

    private fun loadInto(view: ImageView, url: String?) {
        if (view.tag == url) return
        view.tag = url
        if (url == null) return view.setImageResource(R.drawable.station_art)
        thumbs.get(url)?.let { return view.setImageBitmap(it) }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            if (view.tag != url) return@launch
            if (bmp != null) { thumbs.put(url, bmp); view.setImageBitmap(bmp) }
            else view.setImageResource(R.drawable.station_art)
        }
    }

    private fun skip(direction: String) {
        val c = controller
        if (c != null) {
            c.sendCustomCommand(
                SessionCommand(if (direction == "next") RadioService.CMD_NEXT else RadioService.CMD_BACK, Bundle.EMPTY),
                Bundle.EMPTY
            )
        } else {
            lifecycleScope.launch { api.skip(direction) }
        }
        lifecycleScope.launch {
            delay(1500); api.nowPlaying()?.let { showNowPlaying(it) }
        }
    }

    private fun loadArt(url: String?) {
        if (url == lastArt) return
        lastArt = url
        artJob?.cancel()
        if (url == null || url.startsWith("android.resource")) {
            b.artwork.setImageResource(R.drawable.station_art)
            return
        }
        artJob = lifecycleScope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                runCatching {
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp != null) b.artwork.setImageBitmap(bmp) else b.artwork.setImageResource(R.drawable.station_art)
        }
    }


    // ------------------------------------------------------------------ settings
    private fun showSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
