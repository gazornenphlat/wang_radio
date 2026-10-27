package com.wangradio.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.wangradio.app.databinding.ActivitySettingsBinding
import com.wangradio.app.databinding.RowSettingBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Settings: this phone's connection (station address + password), then every setting in the
 * station's radio.toml, read from and saved to the station's /settings.json. Same rules as the
 * web settings page: open from the home network, or anywhere with [stream] admin_password.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var api: StationApi
    private var items: List<Setting> = emptyList()
    private val changes = linkedMapOf<String, Any>()     // setting id -> new value
    private val rows = mutableMapOf<String, RowSettingBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        api = StationApi(this)

        b.serverUrl.setText(Prefs.serverUrl(this))
        b.serverKey.setText(Prefs.key(this))
        b.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        b.saveConnection.setOnClickListener { saveConnection() }
        b.unlock.setOnClickListener { unlock() }
        b.adminPassword.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) unlock(); false }
        b.saveButton.setOnClickListener { save() }
        paintTheme()
        listOf(b.themeSystem to "system", b.themeLight to "light", b.themeDark to "dark").forEach { (v, t) ->
            v.setOnClickListener {
                if (Prefs.theme(this) == t) return@setOnClickListener
                Prefs.setTheme(this, t)
                paintTheme()
                WangApp.applyTheme(t)          // every open screen redraws in the new colours
            }
        }
        b.find.addTextChangedListener(watcher { render() })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (changes.isEmpty()) return leave()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.leave_title)
                    .setPositiveButton(R.string.leave_discard) { _, _ -> leave() }
                    .setNegativeButton(R.string.leave_stay, null)
                    .show()
            }
        })
        load()
    }

    private fun leave() {
        if (isTaskRoot) startActivity(Intent(this, ChannelsActivity::class.java))
        finish()
    }

    // ------------------------------------------------------------------ this phone
    private fun paintTheme() {
        val now = Prefs.theme(this)
        listOf(b.themeSystem to "system", b.themeLight to "light", b.themeDark to "dark").forEach { (v, t) ->
            v.setBackgroundColor(color(if (t == now) R.color.border else R.color.card))
            v.setTextColor(color(if (t == now) R.color.card else R.color.muted))
        }
    }

    private fun saveConnection() {
        Prefs.save(this, b.serverUrl.text.toString(), b.serverKey.text.toString())
        b.serverUrl.setText(Prefs.serverUrl(this))
        Toast.makeText(this, R.string.connection_saved, Toast.LENGTH_SHORT).show()
        changes.clear()
        load()
    }

    private fun unlock() {
        Prefs.setAdmin(this, b.adminPassword.text.toString())
        load()
    }

    // ------------------------------------------------------------------ station settings
    private fun load() {
        if (!Prefs.isConfigured(this)) {
            return gate(R.string.gate_setup_k, R.string.gate_setup_t, R.string.gate_setup_b)
        }
        lifecycleScope.launch {
            when (val r = api.settings()) {
                is SettingsResult.Loaded -> {
                    items = r.items
                    b.gate.visibility = View.GONE
                    b.tools.visibility = View.VISIBLE
                    b.restartNote.visibility = View.VISIBLE
                    b.saveBar.visibility = View.VISIBLE
                    b.count.text = getString(R.string.n_settings, items.size)
                    render()
                }
                is SettingsResult.Denied -> when (r.reason) {
                    "local" -> gate(R.string.gate_local_k, R.string.gate_local_t, R.string.gate_local_b)
                    "admin" -> {
                        val tried = Prefs.admin(this@SettingsActivity).isNotEmpty()
                        gate(R.string.gate_admin_k, R.string.gate_admin_t,
                            if (tried) R.string.gate_admin_wrong else R.string.gate_admin_b, askAdmin = true)
                    }
                    else -> gate(R.string.gate_offline_k, R.string.gate_offline_t, R.string.gate_offline_b)
                }
                SettingsResult.Offline -> gate(R.string.gate_offline_k, R.string.gate_offline_t, R.string.gate_offline_b)
            }
        }
    }

    private fun gate(kicker: Int, title: Int, body: Int, askAdmin: Boolean = false) {
        items = emptyList()
        b.groups.removeAllViews()
        b.tools.visibility = View.GONE
        b.restartNote.visibility = View.GONE
        b.saveBar.visibility = View.GONE
        b.gate.visibility = View.VISIBLE
        b.gateKicker.setText(kicker)
        b.gateTitle.text = getText(title)
        b.gateBody.setText(body)
        b.adminBox.visibility = if (askAdmin) View.VISIBLE else View.GONE
        if (askAdmin) b.adminPassword.setText("")
    }

    private fun niceTable(t: String): String {
        if (t.isEmpty()) return "General"
        val parts = t.split("#")
        val name = parts[0].split(".").joinToString(" › ") { it.replace('_', ' ') }
        return if (parts.size > 1) name + " " + roman(parts[1].toIntOrNull() ?: 1) else name
    }

    private fun rawTable(t: String): String {
        if (t.isEmpty()) return "top of file"
        val parts = t.split("#")
        return if (parts.size > 1) "[[${parts[0]}]] #${parts[1]}" else "[$t]"
    }

    private fun render() {
        val q = b.find.text.toString().trim().lowercase()
        val groups = items.filter {
            q.isEmpty() || "${it.key} ${it.table} ${it.help}".lowercase().contains(q)
        }.groupBy { it.table }
        b.groups.removeAllViews()
        rows.clear()
        if (groups.isEmpty()) {
            b.groups.addView(TextView(this).apply {
                text = getText(R.string.nothing_matches)
                typeface = font(R.font.fraunces); textSize = 24f
                setTextColor(color(R.color.ink))
                background = ContextCompat.getDrawable(context, R.drawable.box_4)
                setPadding(dp(20), dp(20), dp(24), dp(24))
            })
        }
        var n = 0
        for ((table, list) in groups) {
            n++
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.box_4)
                setPadding(dp(3), dp(3), dp(7), dp(7))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(40) }
            }
            card.addView(header("${roman(n)}. ${rawTable(table)}", niceTable(table)))
            list.forEachIndexed { i, s -> card.addView(row(s, first = i == 0)) }
            b.groups.addView(card)
        }
        sync()
    }

    private fun header(label: String, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(color(R.color.border))
        setPadding(dp(20), dp(12), dp(20), dp(12))
        addView(kicker(label).apply {
            setTextColor(color(R.color.card))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        addView(TextView(context).apply {
            text = title
            typeface = Typeface.create(font(R.font.fraunces), Typeface.ITALIC)
            textSize = 16f
            setTextColor(color(R.color.card))
        })
    }

    private fun row(s: Setting, first: Boolean): View {
        val r = RowSettingBinding.inflate(LayoutInflater.from(this), null, false)
        rows[s.id] = r
        r.srRule.visibility = if (first) View.GONE else View.VISIBLE
        r.srKind.setText(when (s.kind) {
            "bool" -> R.string.kind_bool; "number" -> R.string.kind_number; "secret" -> R.string.kind_secret
            "raw" -> R.string.kind_raw; else -> R.string.kind_text
        })
        r.srName.text = s.key.replace('_', ' ')
        r.srHelp.text = s.help
        r.srHelp.visibility = if (s.help.isEmpty()) View.GONE else View.VISIBLE
        r.srControl.addView(control(s))
        paintChanged(s.id)
        return r.root
    }

    private fun control(s: Setting): View {
        val current: Any? = changes[s.id] ?: s.value
        if (s.kind == "bool") return toggle(s, current == true)
        return EditText(this).apply {
            background = ContextCompat.getDrawable(context, R.drawable.field)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(48)
            textSize = 14f
            typeface = font(R.font.dmsans)
            setTextColor(color(R.color.ink))
            setHintTextColor(color(R.color.muted))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            when (s.kind) {
                "number" -> inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
                "secret" -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = getString(if (s.isSet) R.string.secret_set else R.string.secret_unset)
                }
                "raw" -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                    gravity = Gravity.TOP or Gravity.START
                }
                else -> inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            val shown = when {
                s.kind == "secret" -> (changes[s.id] as? String) ?: ""
                s.kind == "number" -> numberText(current)
                else -> current?.toString() ?: ""
            }
            setText(shown)
            addTextChangedListener(watcher { mark(s, text.toString()) })
        }
    }

    private fun numberText(v: Any?): String = v?.toString() ?: ""

    private fun toggle(s: Setting, on: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.field)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = ContextCompat.getDrawable(context, R.drawable.divider_v)
            layoutParams = android.widget.FrameLayout.LayoutParams(-2, -2)
        }
        val onBtn = kicker(getString(R.string.on))
        val offBtn = kicker(getString(R.string.off))
        fun paint(v: Boolean) {
            listOf(onBtn to v, offBtn to !v).forEach { (t, active) ->
                t.setBackgroundColor(color(if (active) R.color.border else R.color.card))
                t.setTextColor(color(if (active) R.color.card else R.color.muted))
            }
        }
        for ((t, v) in listOf(onBtn to true, offBtn to false)) {
            t.setPadding(dp(18), dp(14), dp(18), dp(14))
            t.setOnClickListener { paint(v); mark(s, v) }
            box.addView(t)
        }
        paint(on)
        return box
    }

    private fun mark(s: Setting, v: Any) {
        val same = if (s.kind == "secret") v == "" else v.toString() == (
            if (s.kind == "number") numberText(s.value) else s.value?.toString() ?: "")
        if (same) changes.remove(s.id) else changes[s.id] = v
        paintChanged(s.id)
        sync()
    }

    /** Changed settings turn orange (the design's "active" colour). */
    private fun paintChanged(id: String) {
        val r = rows[id] ?: return
        val on = changes.containsKey(id)
        r.srBody.setBackgroundColor(color(if (on) R.color.primary else R.color.card))
        r.srHelp.setTextColor(color(if (on) R.color.on_primary else R.color.muted))
        r.srName.setTextColor(color(if (on) R.color.on_primary else R.color.ink))
        r.srKind.setTextColor(color(if (on) R.color.on_primary else R.color.ink))
    }

    private fun sync() {
        val n = changes.size
        b.saveMsg.text = if (n == 0) getString(R.string.no_changes)
        else resources.getQuantityString(R.plurals.unsaved_changes, n, n)
        b.saveButton.isEnabled = n > 0
        b.saveButton.alpha = if (n > 0) 1f else 0.4f
    }

    private fun save() {
        if (changes.isEmpty()) return
        val body = JSONObject()
        changes.forEach { (k, v) -> body.put(k, v) }
        b.saveButton.isEnabled = false
        lifecycleScope.launch {
            val err = api.saveSettings(body)
            if (err == null) {
                Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_LONG).show()
                changes.clear()
                load()
            } else {
                Toast.makeText(this@SettingsActivity, getString(R.string.settings_save_failed, err), Toast.LENGTH_LONG).show()
                sync()
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun kicker(text: String) = TextView(this).apply {
        this.text = text
        setAllCaps(true)
        letterSpacing = 0.2f
        textSize = 10f
        typeface = Typeface.create(font(R.font.dmsans), Typeface.BOLD)
        setTextColor(color(R.color.ink))
        includeFontPadding = false
    }

    private fun font(id: Int): Typeface = ResourcesCompat.getFont(this, id) ?: Typeface.DEFAULT
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun watcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = after()
    }
}

private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII",
    "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX")

fun roman(n: Int): String = ROMAN.getOrNull(n - 1) ?: n.toString()
