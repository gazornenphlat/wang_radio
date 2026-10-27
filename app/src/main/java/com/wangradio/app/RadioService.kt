package com.wangradio.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays the station and exposes it to the phone's media controls, the lock screen,
 * Bluetooth, and Android Auto. Thumbs up/down appear as custom buttons everywhere.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RadioService : MediaLibraryService() {

    companion object {
        const val ROOT_ID = "root"
        const val LIVE_ID = "live"
        const val CHANNEL_PREFIX = "ch:"              // + station id (one per music folder)
        const val CMD_STAR = "wang.STAR"           // + 1..5  (one button per star)
        const val CMD_NEXT = "wang.NEXT"
        const val CMD_BACK = "wang.BACK"
        const val CMD_REFRESH = "wang.REFRESH"
        private val STARS = (1..5).map { SessionCommand("$CMD_STAR$it", Bundle.EMPTY) }
        private val NEXT = SessionCommand(CMD_NEXT, Bundle.EMPTY)
        private val BACK = SessionCommand(CMD_BACK, Bundle.EMPTY)
        private val REFRESH = SessionCommand(CMD_REFRESH, Bundle.EMPTY)
    }

    private lateinit var player: ExoPlayer
    private lateinit var stationPlayer: StationPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var api: StationApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var now: NowPlaying? = null
    private var retries = 0
    private var reconnecting = false

    private val stationArt: Uri
        get() = Uri.parse("android.resource://$packageName/${R.drawable.station_art}")

    override fun onCreate() {
        super.onCreate()
        api = StationApi(this)
        // Live radio: start quickly, and after a hiccup resume after 2 s instead of the default 5 s.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .build()
        // Generous timeouts: mobile data + the Cloudflare tunnel can pause for a few seconds.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("WANGRadio/1.0 (Android)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, http)))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Wrap the player so Next / Previous (app, lock screen, Bluetooth, steering wheel,
        // Android Auto) control the station instead of a local playlist.
        stationPlayer = StationPlayer(player)
        session = MediaLibrarySession.Builder(this, stationPlayer, callback)
            .setSessionActivity(openApp)
            .build()
        session.setCustomLayout(starButtons(0))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ library (Android Auto browsing)
    private fun liveItem(np: NowPlaying? = now): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(np?.title ?: stationName())
            .setArtist(np?.artist?.ifEmpty { null } ?: stationName())
            .setAlbumTitle(np?.album?.ifEmpty { null })
            .setStation(stationName())
            .setArtworkUri(np?.artUrl?.let { Uri.parse(it) } ?: stationArt)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()
        return MediaItem.Builder()
            .setMediaId(LIVE_ID)
            .setUri(api.streamUrl())
            .setMediaMetadata(meta)
            .build()
    }

    private val rootItem: MediaItem
        get() = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(R.string.app_name))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build()
            ).build()

    private fun stationName() = Prefs.channelName(this).ifEmpty { getString(R.string.app_name) }

    private var lastChannels: List<Channel> = emptyList()

    /** One entry per station in Android Auto's list. */
    private fun channelItem(c: Channel): MediaItem = MediaItem.Builder()
        .setMediaId(CHANNEL_PREFIX + c.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(c.name)
                .setSubtitle("\u266a " + c.nowPlaying)
                .setStation(c.name)
                .setArtworkUri(c.artUrl?.let { Uri.parse(it) } ?: stationArt)
                .setIsPlayable(true).setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build()
        ).build()

    /** Switch to another station (from Android Auto's list or the phone's main screen). */
    private fun selectChannel(id: String, name: String?) {
        if (id == Prefs.channel(this)) return
        val n = name ?: lastChannels.firstOrNull { it.id == id }?.name ?: id
        Prefs.setChannel(this, id, n)
        now = null
        stationPlayer.clearMeta()
    }

    private fun browseItem(): MediaItem =
        // Browse list shows the station with its logo (not the current song's cover).
        liveItem(null).buildUpon().setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(getString(R.string.app_name))
                .setSubtitle(getString(R.string.tagline))
                .setArtworkUri(stationArt)
                .setIsPlayable(true).setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build()
        ).build()

    private val callback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession, controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .apply { STARS.forEach { add(it) } }
                .add(NEXT).add(BACK).add(REFRESH).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds)
                .setCustomLayout(starButtons(now?.stars ?: 0))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_NEXT -> skip("next")
                CMD_BACK -> skip("back")
                CMD_REFRESH -> refreshNow()
                else -> customCommand.customAction.removePrefix(CMD_STAR).toIntOrNull()
                    ?.takeIf { customCommand.customAction.startsWith(CMD_STAR) && it in 1..5 }
                    ?.let { tapStar(it) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // Steering-wheel, Bluetooth and headset next/previous buttons.
        override fun onMediaButtonEvent(
            session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent
        ): Boolean {
            val key = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ?: return false
            if (key.action != android.view.KeyEvent.ACTION_DOWN) {
                return key.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT ||
                    key.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }
            return when (key.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { skip("next"); true }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { skip("back"); true }
                else -> false
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))

        // Android Auto's list: every station on the server.
        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val list = api.channels()
                if (list != null) lastChannels = list
                val items = if (list.isNullOrEmpty()) listOf(browseItem()) else list.map { channelItem(it) }
                result.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return result
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val ch = lastChannels.firstOrNull { CHANNEL_PREFIX + it.id == mediaId }
            return Futures.immediateFuture(
                when {
                    mediaId == ROOT_ID -> LibraryResult.ofItem(rootItem, null)
                    ch != null -> LibraryResult.ofItem(channelItem(ch), null)
                    else -> LibraryResult.ofItem(browseItem(), null)
                }
            )
        }

        // Controllers (Android Auto, the app) only send a media ID; we fill in the stream URL.
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            if (!Prefs.isConfigured(this@RadioService)) {
                Toast.makeText(this@RadioService, R.string.not_configured, Toast.LENGTH_LONG).show()
            }
            mediaItems.firstOrNull()?.takeIf { it.mediaId.startsWith(CHANNEL_PREFIX) }?.let {
                selectChannel(it.mediaId.removePrefix(CHANNEL_PREFIX), it.mediaMetadata.title?.toString())
            }
            refreshNow()
            return Futures.immediateFuture(mediaItems.map { liveItem() }.toMutableList())
        }

        // Lets Android Auto / Bluetooth "play" start the station even if the app wasn't open.
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(listOf(liveItem()), 0, C.TIME_UNSET)
            )
    }

    // ------------------------------------------------------------------ next / back
    private inner class StationPlayer(p: Player) : ForwardingPlayer(p) {
        // Song info is published here instead of swapping the player's media item,
        // so updating the title/artwork never touches (or restarts) the audio connection.
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()
        private var meta: MediaMetadata? = null

        override fun addListener(listener: Player.Listener) {
            super.addListener(listener); listeners.add(listener)
        }
        override fun removeListener(listener: Player.Listener) {
            super.removeListener(listener); listeners.remove(listener)
        }
        override fun getMediaMetadata(): MediaMetadata = meta ?: super.getMediaMetadata()
        override fun getCurrentMediaItem(): MediaItem? =
            super.getCurrentMediaItem()?.let { item -> meta?.let { item.buildUpon().setMediaMetadata(it).build() } ?: item }

        fun clearMeta() {
            meta = null
        }

        fun publish(m: MediaMetadata) {
            meta = m
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
            listeners.forEach {
                it.onMediaMetadataChanged(m)
                it.onEvents(this, events)
            }
        }

        // The standard previous/next are hidden so Android Auto fills those main spots with our
        // own buttons, in our order: the five stars first, then back and next.
        private val hidden = listOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().removeAll(*hidden.toIntArray()).build()
        override fun isCommandAvailable(command: Int) = command !in hidden && super.isCommandAvailable(command)
        override fun seekToNext() = skip("next")
        override fun seekToNextMediaItem() = skip("next")
        override fun seekToPrevious() = skip("back")
        override fun seekToPreviousMediaItem() = skip("back")
    }

    private fun skip(direction: String) {
        scope.launch {
            if (!api.skip(direction)) {
                toast(getString(R.string.skip_failed))
            } else if (player.playWhenReady) {
                // The station switches at once, but the player still holds a few seconds of the
                // old song. Reconnect so it jumps straight to the new one.
                delay(400)
                player.stop()
                player.setMediaItem(liveItem())
                player.prepare()
                player.play()
            }
            // Refresh song info a couple of times.
            delay(1500); refreshNow()
            delay(3000); refreshNow()
        }
    }

    // ------------------------------------------------------------------ star rating (Android Auto)
    // Five star buttons first (Android Auto shows them in its main spots), then back and next.
    // Tapping a star saves it right away; 1 star = record scratch + skip the song.
    private fun starButtons(stars: Int): ImmutableList<CommandButton> {
        val list = ImmutableList.builder<CommandButton>()
        for (i in 1..5) {
            list.add(
                CommandButton.Builder()
                    .setDisplayName(resources.getQuantityString(R.plurals.n_stars, i, i))
                    .setIconResId(if (i <= stars) R.drawable.ic_star else R.drawable.ic_star_outline)
                    .setSessionCommand(STARS[i - 1])
                    .build()
            )
        }
        list.add(
            CommandButton.Builder().setDisplayName(getString(R.string.previous_song))
                .setIconResId(R.drawable.ic_skip_previous).setSessionCommand(BACK).build()
        )
        list.add(
            CommandButton.Builder().setDisplayName(getString(R.string.next_song))
                .setIconResId(R.drawable.ic_skip_next).setSessionCommand(NEXT).build()
        )
        return list.build()
    }

    private fun tapStar(n: Int) {
        val id = now?.id
        if (id == null) {
            toast(getString(R.string.cant_rate_break))
            return
        }
        session.setCustomLayout(starButtons(n))          // show it immediately
        scope.launch {
            api.rateStars(id, n)
                .onSuccess { saved ->
                    if (now?.id == id) now = now?.copy(stars = saved)
                    session.setCustomLayout(starButtons(now?.stars ?: saved))
                    toast(
                        if (saved == 1) getString(R.string.one_star_skip)
                        else getString(R.string.stars_saved, "\u2605".repeat(saved))
                    )
                    if (saved == 1) { delay(1500); refreshNow(); delay(3000); refreshNow() }
                }
                .onFailure {
                    toast(getString(R.string.rating_failed, it.message ?: ""))
                    session.setCustomLayout(starButtons(now?.stars ?: 0))
                }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ now playing (artwork etc.)
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refreshNow()
                delay(5000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun refreshNow() {
        scope.launch {
            val np = api.nowPlaying() ?: return@launch
            val changed = np.id != now?.id || np.title != now?.title ||
                (np.id == null && np.artUrl != now?.artUrl)
            val ratingChanged = np.stars != now?.stars
            now = np
            if (changed && player.mediaItemCount > 0) {
                // Same stream URL, new metadata: ExoPlayer updates it without interrupting audio.
                stationPlayer.publish(liveItem(np).mediaMetadata)
            }
            if (changed || ratingChanged) {
                session.setCustomLayout(starButtons(np.stars))
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                retries = 0
                startPolling()
            } else if (!player.playWhenReady) {
                stopPolling()
            }
        }

        // Stream dropped (Wi-Fi to cellular, tunnel restart...): reconnect automatically.
        override fun onPlayerError(error: PlaybackException) {
            // Show why the stream stopped, so problems can be diagnosed.
            val why = error.cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: ""
            Toast.makeText(this@RadioService, "Stream dropped: ${error.errorCodeName} $why".take(200),
                Toast.LENGTH_LONG).show()
            android.util.Log.w("WANGRadio", "Playback error", error)
            if (!player.playWhenReady || reconnecting) return
            reconnecting = true
            val wait = minOf(30_000L, 2_000L * (1 + retries++))
            scope.launch {
                delay(wait)
                reconnecting = false
                if (player.playWhenReady) {
                    player.stop()                     // fully close the old connection first
                    player.setMediaItem(liveItem())   // picks up a changed server URL too
                    player.prepare()
                    player.play()
                }
            }
        }
    }
}
