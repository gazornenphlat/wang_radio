package com.wangradio.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Where the station lives (saved on the phone). */
object Prefs {
    private const val FILE = "wang_radio"

    fun serverUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, 0).getString("url", "")!!.trim().trimEnd('/')

    fun key(ctx: Context): String =
        ctx.getSharedPreferences(FILE, 0).getString("key", "")!!.trim()

    fun save(ctx: Context, url: String, key: String) {
        var u = url.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        ctx.getSharedPreferences(FILE, 0).edit().putString("url", u).putString("key", key.trim()).apply()
    }

    fun isConfigured(ctx: Context) = serverUrl(ctx).isNotEmpty()

    /** "system" (follow Android), "light" or "dark". */
    fun theme(ctx: Context): String = ctx.getSharedPreferences(FILE, 0).getString("theme", "system")!!
    fun setTheme(ctx: Context, theme: String) {
        ctx.getSharedPreferences(FILE, 0).edit().putString("theme", theme).apply()
    }

    /** [stream] admin_password from radio.toml, needed to change settings from outside the house. */
    fun admin(ctx: Context): String = ctx.getSharedPreferences(FILE, 0).getString("admin", "")!!
    fun setAdmin(ctx: Context, pw: String) {
        ctx.getSharedPreferences(FILE, 0).edit().putString("admin", pw.trim()).apply()
    }

    /** The station (folder) picked on the main screen; empty = the server's first station. */
    fun channel(ctx: Context): String = ctx.getSharedPreferences(FILE, 0).getString("channel", "")!!
    fun channelName(ctx: Context): String = ctx.getSharedPreferences(FILE, 0).getString("channel_name", "")!!
    fun setChannel(ctx: Context, id: String, name: String) {
        ctx.getSharedPreferences(FILE, 0).edit().putString("channel", id).putString("channel_name", name).apply()
    }
}

data class Channel(
    val id: String,
    val name: String,
    val nowPlaying: String,
    val artUrl: String?,
    val listeners: Int,
)

data class TrackRef(val id: String, val title: String, val artist: String, val artUrl: String?)

data class NowPlaying(
    val id: String?,          // null during station breaks (IDs, time, news, weather)
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String?,
    val rating: String?,      // "up", "down" or null
    val stars: Int,           // 0 = not rated, 1-5
    val station: String,
    val previous: TrackRef?,
    val next: TrackRef?,
)

/** One line of radio.toml, as the station's /settings.json describes it. */
data class Setting(
    val id: String,
    val table: String,        // "stream", "ai.personas.default", "channels#1" ...
    val key: String,
    val kind: String,         // text, number, bool, secret, raw
    val value: Any?,          // secrets are never sent ("" here)
    val isSet: Boolean,
    val help: String,
)

sealed class SettingsResult {
    data class Loaded(val file: String, val items: List<Setting>) : SettingsResult()
    data class Denied(val reason: String) : SettingsResult()   // "local" or "admin"
    object Offline : SettingsResult()
}

/** Talks to radio.py. */
class StationApi(private val ctx: Context) {
    // Server root, used for absolute paths the server hands back (artwork links etc.)
    private val base get() = Prefs.serverUrl(ctx)
    // The chosen station's paths: /c/<id>/... (old servers / no choice: the root)
    private val stationBase: String
        get() = Prefs.channel(ctx).let { if (it.isEmpty()) base else "$base/c/${Uri.encode(it)}" }
    private val key get() = Prefs.key(ctx)

    private fun withKey(path: String, extra: String = "", root: Boolean = false): String {
        val sep = if (path.contains("?")) "&" else "?"
        val k = if (key.isNotEmpty()) "key=" + Uri.encode(key) else ""
        val q = listOf(k, extra).filter { it.isNotEmpty() }.joinToString("&")
        val b = if (root) base else stationBase
        return if (q.isEmpty()) b + path else b + path + sep + q
    }

    /** The stations (one per music folder) offered by the server. */
    suspend fun channels(): List<Channel>? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        runCatching {
            val arr = org.json.JSONArray(get(withKey("/channels.json", root = true)))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Channel(
                    id = o.getString("id"),
                    name = o.optString("name", o.getString("id")),
                    nowPlaying = o.optString("now_playing"),
                    artUrl = o.optString("art").takeIf { it.isNotEmpty() }?.let { base + it },
                    listeners = o.optInt("listeners"),
                )
            }
        }.getOrNull()
    }

    /** Stream URL. noicy=1 stops in-stream titles from overwriting the app's nicer metadata. */
    fun streamUrl(): String = withKey("/stream", "noicy=1")

    suspend fun nowPlaying(): NowPlaying? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        runCatching {
            val j = JSONObject(get(withKey("/status.json")))
            val t = j.optJSONObject("track") ?: JSONObject()
            val id = t.optString("id").takeIf { it.isNotEmpty() && it != "null" }
            val art = j.optString("art").takeIf { it.isNotEmpty() }?.let { base + it }
            NowPlaying(
                id = id,
                title = t.optString("title", j.optString("now_playing")),
                artist = t.optString("artist"),
                album = t.optString("album"),
                artUrl = art,
                rating = j.optString("rating").takeIf { it == "up" || it == "down" },
                stars = j.optInt("stars", 0),
                station = j.optString("station", "WANG Radio"),
                previous = ref(j.optJSONObject("previous")),
                next = ref(j.optJSONObject("next")),
            )
        }.getOrNull()
    }

    private fun ref(o: JSONObject?): TrackRef? = o?.let {
        TrackRef(
            id = it.optString("id"),
            title = it.optString("title"),
            artist = it.optString("artist"),
            artUrl = it.optString("art").takeIf { a -> a.isNotEmpty() }?.let { a -> base + a },
        )
    }

    /** Skip on the station: "next" (plays next.mp3, then the next song) or "back" (back.mp3, then the previous song). */
    suspend fun skip(direction: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(withKey("/skip", "dir=$direction")).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.doOutput = true
            c.outputStream.use { it.write(ByteArray(0)) }
            c.responseCode in 200..299
        }.getOrDefault(false)
    }

    /** Swaps the up-next song for another one. Returns the new up-next song. */
    suspend fun skipUpNext(): TrackRef? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(withKey("/skip", "dir=upnext")).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.doOutput = true
            c.outputStream.use { it.write(ByteArray(0)) }
            if (c.responseCode !in 200..299) return@runCatching null
            ref(JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("next"))
        }.getOrNull()
    }

    /** Sets 1-5 stars (0 clears). The station copies it to Plex. Returns the stars now saved. */
    suspend fun rateStars(id: String, stars: Int): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(withKey("/rate")).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("id", id).put("stars", stars)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode == 409) error("Nothing to rate during a station break")
            if (c.responseCode !in 200..299) error("Server said ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().readText()).optInt("stars", 0)
        }
    }

    /** Sends a thumbs up/down. Returns the rating now saved on the server ("up", "down" or null if cleared). */
    suspend fun rate(id: String?, rating: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(withKey("/rate")).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("rating", rating).apply { if (id != null) put("id", id) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode == 409) error("Nothing to rate during a station break")
            if (c.responseCode !in 200..299) error("Server said ${c.responseCode}")
            val r = JSONObject(c.inputStream.bufferedReader().readText()).optString("rating")
            r.takeIf { it == "up" || it == "down" }
        }
    }

    /** Every setting in the station's radio.toml. */
    suspend fun settings(): SettingsResult = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext SettingsResult.Offline
        runCatching {
            val c = URL(withKey("/settings.json", root = true)).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            Prefs.admin(ctx).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("X-Admin-Password", it) }
            when (c.responseCode) {
                403 -> {
                    val body = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val why = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                    // a 403 without our JSON means the listener password (?key=) is wrong
                    SettingsResult.Denied(if (why == "local") "local" else if (why.isEmpty()) "key" else "admin")
                }
                in 200..299 -> {
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val arr = j.getJSONArray("items")
                    SettingsResult.Loaded(j.optString("file"), (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Setting(
                            id = o.getString("id"), table = o.optString("table"), key = o.getString("key"),
                            kind = o.optString("kind", "text"), value = o.opt("value"),
                            isSet = o.optBoolean("is_set"), help = o.optString("help"),
                        )
                    })
                }
                404 -> SettingsResult.Denied("old")          // station hasn't been updated yet
                else -> SettingsResult.Offline
            }
        }.getOrDefault(SettingsResult.Offline)
    }

    /** Saves changed settings ({id: value}). Returns null when saved, or the station's error. */
    suspend fun saveSettings(changes: JSONObject): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(withKey("/settings.json", root = true)).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            Prefs.admin(ctx).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("X-Admin-Password", it) }
            c.outputStream.use { it.write(changes.toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val j = runCatching { JSONObject(text) }.getOrNull()
            if (ok && j?.optBoolean("ok") == true) null
            else j?.optString("error")?.takeIf { it.isNotEmpty() && it != "null" } ?: "Server said ${c.responseCode}"
        }.getOrElse { it.message ?: "Can't reach the station" }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
