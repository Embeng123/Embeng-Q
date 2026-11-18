package com.embeng.q

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.method.ScrollingMovementMethod
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.divider.MaterialDivider
import usqueandroid.Usqueandroid
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object { private const val REQ_VPN = 1001 }
    data class AppEntry(val label: String, val packageName: String)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("embengq", MODE_PRIVATE) }
    private val configFile by lazy { File(filesDir, "config.json") }

    private lateinit var endpointInput: TextInputEditText
    private lateinit var sniInput: TextInputEditText
    private lateinit var statusBanner: TextView
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var configStateText: TextView
    private lateinit var logText: TextView
    private lateinit var ipv4Text: TextView
    private lateinit var ipv6Text: TextView
    private lateinit var modeValue: TextView
    private lateinit var modeHint: TextView
    private lateinit var splitModeSwitch: MaterialSwitch
    private lateinit var appSearchInput: TextInputEditText
    private lateinit var appSection: MaterialCardView
    private lateinit var appListContainer: LinearLayout
    private lateinit var appCountText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var defaultBtn: MaterialButton
    private lateinit var homeProfileSpinner: Spinner
    private lateinit var currentProfileText: TextView
    private lateinit var profileSpinner: Spinner
    private lateinit var profileNameInput: TextInputEditText
    private lateinit var saveNewProfileBtn: MaterialButton
    private lateinit var overwriteProfileBtn: MaterialButton
    private lateinit var deleteProfileBtn: MaterialButton
    private lateinit var languageIdButton: MaterialButton
    private lateinit var languageEnButton: MaterialButton
    private lateinit var selectAllAppsBtn: MaterialButton
    private lateinit var clearAllAppsBtn: MaterialButton

    private var useEnglish = false
    private var vpnRunning = false
    private var vpnGranted = false
    private var configDirty = false
    private var appsLoaded = false
    private var suppressProfileSelection = false
    private var currentPageIndex = 0
    private var pageSwitcher: ((Int) -> Unit)? = null
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSpeedTs = 0L
    private val speedTicker = object : Runnable {
        override fun run() {
            updateSpeedLine()
            handler.postDelayed(this, 1000)
        }
    }
    private val profiles = linkedMapOf<String, Triple<String, String, Int>>()
    private val allApps = mutableListOf<AppEntry>()
    private val selectedPackages = linkedSetOf<String>()

    private val bg = Color.rgb(255, 248, 242)
    private val surface = Color.rgb(255, 255, 255)
    private val surface2 = Color.rgb(255, 238, 224)
    private val primary = Color.rgb(246, 196, 155) // soft orange / oranye muda
    private val darkAccent = Color.rgb(205, 126, 73)
    private val mango = Color.rgb(255, 224, 195)
    private val onPrimary = Color.rgb(82, 49, 28)
    private val textColor = Color.rgb(48, 39, 32)
    private val subText = Color.rgb(111, 91, 76)
    private val outline = Color.rgb(232, 211, 194)
    private val green = Color.rgb(49, 145, 104)
    private val danger = Color.rgb(188, 77, 77)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Embeng-Q"
        useEnglish = prefs.getBoolean("useEnglish", false)
        buildUi()
        loadSavedState()
        refreshAppList()
        refreshState()
        resetSpeedMeter()
        handler.post(speedTicker)
    }

    override fun onDestroy() {
        handler.removeCallbacks(speedTicker)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - swipeDownX
                val dy = ev.rawY - swipeDownY
                val threshold = dp(72)
                if (kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f) {
                    if (dx < 0 && currentPageIndex < 2) { pageSwitcher?.invoke(currentPageIndex + 1); return true }
                    if (dx > 0 && currentPageIndex > 0) { pageSwitcher?.invoke(currentPageIndex - 1); return true }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun tr(id: String, en: String): String = if (useEnglish) en else id
    private fun setLanguage(en: Boolean) {
        useEnglish = en
        prefs.edit().putBoolean("useEnglish", useEnglish).apply()
        buildUi()
        loadSavedState()
        refreshAppList()
        refreshState()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Embeng-Q"
            subtitle = null
            setTitleTextColor(textColor)
            setSubtitleTextColor(subText)
            setBackgroundColor(Color.rgb(255, 252, 249))
            elevation = dp(2).toFloat()
            setPadding(dp(8), 0, dp(8), 0)
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.rgb(255, 252, 249))
        }
        val pageHost = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        fun pageScroll(child: View): ScrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            (child.parent as? ViewGroup)?.removeView(child)
            addView(child)
        }
        val overviewTab = tabButton(tr("Beranda", "Overview"))
        val configTab = tabButton(tr("Konfigurasi", "Config"))
        val appsTab = tabButton(tr("Aplikasi", "Apps"))
        val tabsList = listOf(overviewTab, configTab, appsTab)
        tabsList.forEachIndexed { index, b ->
            tabs.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (index < 2) rightMargin = dp(8) })
        }

        val homePage = buildHomePage()
        val configPage = buildConfigPage()
        val appsPage = buildAppsPage()
        val titles = listOf("Embeng-Q", tr("Konfigurasi Koneksi", "Connection Config"), tr("Pilih Aplikasi", "Select Apps"))
        val pages = listOf(homePage, configPage, appsPage)
        fun showIndex(index: Int) {
            val safe = index.coerceIn(0, pages.lastIndex)
            currentPageIndex = safe
            toolbar.title = titles[safe]
            toolbar.subtitle = null
            tabsList.forEachIndexed { i, b -> setTabSelected(b, i == safe) }
            if (safe == 2) ensureAppsLoaded()
            pageHost.removeAllViews()
            pageHost.addView(pageScroll(pages[safe]), FrameLayout.LayoutParams(-1, -1))
        }

        overviewTab.setOnClickListener { showIndex(0) }
        configTab.setOnClickListener { showIndex(1) }
        appsTab.setOnClickListener { showIndex(2) }
        pageSwitcher = { showIndex(it) }
        showIndex(0)

        defaultBtn.setOnClickListener {
            val defaultEndpoint = Usqueandroid.getDefaultEndpoint(configFile.absolutePath)
            endpointInput.setText(parseEndpointHost(defaultEndpoint))
            refreshState(tr("Endpoint default dimuat", "Default endpoint loaded"))
        }
        saveNewProfileBtn.setOnClickListener { saveAsNewProfile() }
        overwriteProfileBtn.setOnClickListener { overwriteSelectedProfile() }
        deleteProfileBtn.setOnClickListener { deleteSelectedProfile() }
        connectButton.setOnClickListener { if (vpnRunning) disconnectVpn() else connectVpn() }
        sniInput.addTextChangedListener(dirtyWatcher())
        endpointInput.addTextChangedListener(dirtyWatcher())
        appSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshAppList() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun buildHomePage(): View {
        val content = pageContent()
        val hero = card().apply { setCardBackgroundColor(surface2) }
        val heroBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val eyebrow = TextView(this).apply {
            text = "VPN STATUS"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(onPrimary)
            setTypeface(null, Typeface.BOLD)
        }
        statusBanner = TextView(this).apply {
            text = tr("Tidak Terhubung", "Disconnected")
            textSize = 28f
            setTextColor(onPrimary)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        statusText = TextView(this).apply {
            text = tr("Status: Tidak Terhubung", "Status: Disconnected")
            textSize = 14f
            setTextColor(subText)
            setPadding(0, dp(2), 0, dp(2))
        }
        speedText = TextView(this).apply {
            text = "↓ 0 B/s   ↑ 0 B/s"
            textSize = 14f
            setTextColor(onPrimary)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        homeProfileSpinner = Spinner(this).apply {
            background = round(surface, dp(16), outline)
            setPadding(dp(10), 0, dp(10), 0)
        }
        connectButton = MaterialButton(this).apply {
            text = tr("Hubungkan VPN", "Connect VPN")
            textSize = 15f
            gravity = Gravity.CENTER
            setSingleLine(false)
            maxLines = 2
            setTextColor(Color.rgb(255, 255, 255))
            backgroundTintList = android.content.res.ColorStateList.valueOf(darkAccent)
            cornerRadius = dp(24)
            minHeight = dp(52)
            isAllCaps = false
        }
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actionRow.addView(connectButton, LinearLayout.LayoutParams(-1, dp(52)))
        heroBox.addView(eyebrow)
        heroBox.addView(statusBanner)
        heroBox.addView(statusText)
        heroBox.addView(speedText)
        heroBox.addView(homeProfileSpinner, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) })
        heroBox.addView(actionRow)
        hero.addView(heroBox)
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))

        content.addView(sectionTitle(tr("Mode Proxy", "Proxy Mode")))
        val modeCard = card()
        val modeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        modeValue = TextView(this).apply { text = tr("Mode Global", "Global Mode"); textSize = 18f; setTextColor(textColor); setTypeface(null, Typeface.BOLD) }
        modeHint = TextView(this).apply { text = tr("Semua aplikasi lewat VPN. Di mode pisah, hanya aplikasi yang dipilih di halaman “Aplikasi” yang lewat VPN.", "All apps use VPN. In split mode, only selected apps use VPN."); textSize = 14f; setTextColor(subText); setPadding(0, dp(4), 0, dp(6)) }
        splitModeSwitch = MaterialSwitch(this).apply {
            text = tr("Aktifkan split tunneling", "Enable split tunneling")
            textSize = 16f
            setTextColor(textColor)
            setPadding(0, dp(2), 0, 0)
            setOnCheckedChangeListener { _, _ ->
                markDirty(); saveSelectedApps(); updateModeUi(); refreshAppList()
            }
        }
        modeBox.addView(modeValue)
        modeBox.addView(modeHint)
        modeBox.addView(splitModeSwitch)
        modeCard.addView(modeBox)
        content.addView(modeCard)

        ipv4Text = TextView(this)
        ipv6Text = TextView(this)
        configStateText = TextView(this)
        logText = TextView(this).apply {
            text = tr("Ketuk Hubungkan. Koneksi pertama akan mendaftar otomatis dan memulai VPN.", "Tap Connect. First launch will register automatically and start VPN.")
        }

        val languageCard = card()
        val languageBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        languageIdButton = secondaryButton("Indonesia").apply { setOnClickListener { setLanguage(false) } }
        languageEnButton = secondaryButton("English").apply { setOnClickListener { setLanguage(true) } }
        languageBox.addView(languageIdButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(10) })
        languageBox.addView(languageEnButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        languageCard.addView(languageBox)
        content.addView(languageCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        return content
    }

    private fun buildConfigPage(): View {
        val content = pageContent()
        content.addView(sectionHeader(tr("Konfigurasi Koneksi", "Connection Config"), tr("Beralih antar profil tersimpan dengan cepat.", "Switch between saved profiles quickly.")))

        val profileCard = card()
        val profileBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        profileSpinner = Spinner(this).apply { background = round(surface2, dp(16), outline); setPadding(dp(10), 0, dp(10), 0) }
        profileNameInput = input(tr("Nama Profil", "Profile Name"), tr("contoh: Visa 443 / Cloudflare 443", "e.g. Visa 443 / Cloudflare 443"))
        saveNewProfileBtn = secondaryButton(tr("Simpan sebagai Baru", "Save as New"))
        overwriteProfileBtn = secondaryButton(tr("Timpa Profil Saat Ini", "Overwrite Current"))
        deleteProfileBtn = secondaryButton(tr("Hapus Profil", "Delete Profile"))
        profileBox.addView(TextView(this).apply { text = tr("Profil", "Profiles"); textSize = 18f; setTextColor(textColor); setTypeface(null, Typeface.BOLD) })
        profileBox.addView(TextView(this).apply { text = tr("Simpan SNI / Endpoint saat ini untuk beralih dengan cepat.", "Save current SNI / Endpoint for quick switching."); textSize = 12f; setTextColor(subText); setPadding(0, dp(2), 0, dp(6)) })
        profileBox.addView(profileSpinner, LinearLayout.LayoutParams(-1, dp(42)))
        profileBox.addView(inputWrap(tr("Nama Profil", "Profile Name"), profileNameInput), LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(5) })
        val profileActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profileActions.addView(overwriteProfileBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        profileActions.addView(saveNewProfileBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        profileBox.addView(profileActions, LinearLayout.LayoutParams(-1, dp(48)))
        profileBox.addView(deleteProfileBtn, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        profileCard.addView(profileBox)
        content.addView(profileCard)

        content.addView(sectionTitle(tr("Koneksi Saat Ini", "Current Connection")))
        val config = card()
        val configBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        sniInput = input("SNI", "www.visa.cn")
        endpointInput = input("Endpoint IP", "162.159.198.2")
        defaultBtn = secondaryButton(tr("Muat Endpoint Default", "Load Default Endpoint"))
        configBox.addView(compactInputWrap("SNI", sniInput))
        configBox.addView(compactInputWrap("Endpoint IP", endpointInput))
        configBox.addView(defaultBtn, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(3) })
        config.addView(configBox)
        content.addView(config)
        return content
    }

    private fun buildAppsPage(): View {
        val content = pageContent()
        content.addView(sectionHeader(tr("Pilih Aplikasi", "Select Apps"), tr("Di sini hanya memilih aplikasi. Aktifkan split tunneling di Beranda.", "Only choose apps here. Enable split tunneling on Overview.")))
        appSection = card()
        val appBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        appSearchInput = input(tr("Cari Aplikasi", "Search Apps"), tr("Nama aplikasi atau paket", "App name or package"))
        appCountText = infoLine(tr("0 aplikasi dipilih", "0 apps selected"))
        appListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appBox.addView(inputWrap(tr("Cari", "Search"), appSearchInput))
        appBox.addView(appCountText)
        val appActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectAllAppsBtn = secondaryButton(tr("Pilih Semua", "Select All")).apply { setOnClickListener { selectAllVisibleApps() } }
        clearAllAppsBtn = secondaryButton(tr("Batalkan Semua", "Select None")).apply { setOnClickListener { clearAllVisibleApps() } }
        appActions.addView(selectAllAppsBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        appActions.addView(clearAllAppsBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        appBox.addView(appActions, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        appBox.addView(MaterialDivider(this).apply { dividerColor = outline }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(8); bottomMargin = dp(8) })
        appBox.addView(appListContainer)
        appSection.addView(appBox)
        content.addView(appSection)
        return content
    }

    private fun pageContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        setBackgroundColor(bg)
    }

    private fun tabButton(label: String) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        cornerRadius = dp(18)
        minHeight = dp(36)
        insetTop = 0
        insetBottom = 0
        setTextColor(subText)
        backgroundTintList = android.content.res.ColorStateList.valueOf(surface2)
    }

    private fun setTabSelected(button: MaterialButton, selected: Boolean) {
        button.setTextColor(if (selected) onPrimary else subText)
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected) primary else surface2)
    }

    private fun sectionHeader(title: String, desc: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), 0, dp(2), dp(8))
        addView(TextView(this@MainActivity).apply { text = title; textSize = 20f; setTextColor(textColor); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@MainActivity).apply { text = desc; textSize = 12f; setTextColor(subText); setPadding(0, dp(2), 0, 0) })
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(24).toFloat()
        cardElevation = 0f
        strokeWidth = 1
        strokeColor = outline
        setCardBackgroundColor(surface)
        useCompatPadding = false
    }
    private fun secondaryButton(label: String) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        cornerRadius = dp(22)
        setTextColor(onPrimary)
        backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
        strokeWidth = 0
        elevation = dp(1).toFloat()
    }
    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s
        textSize = 14f
        setTextColor(subText)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(4), dp(10), dp(4), dp(6))
    }
    private fun input(label: String, hint: String) = TextInputEditText(this).apply {
        setHint(hint)
        setTextColor(textColor)
        setHintTextColor(Color.rgb(150, 123, 103))
        textSize = 14f
        isSingleLine = true
    }
    private fun inputWrap(label: String, edit: TextInputEditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(5) }
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subText)
            setPadding(dp(2), 0, dp(2), dp(4))
        })
        addView(TextInputLayout(this@MainActivity).apply {
            isHintEnabled = false
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = surface
            setBoxCornerRadii(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat())
            setBoxStrokeColor(primary)
            addView(edit)
        }, LinearLayout.LayoutParams(-1, dp(50)))
    }
    private fun compactInputWrap(label: String, edit: TextInputEditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(2) }
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subText)
            setPadding(dp(2), 0, dp(2), dp(2))
        })
        addView(TextInputLayout(this@MainActivity).apply {
            isHintEnabled = false
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = surface
            setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat())
            setBoxStrokeColor(primary)
            addView(edit)
        }, LinearLayout.LayoutParams(-1, dp(42)))
    }
    private fun pill(s: String) = TextView(this).apply {
        text = s
        setTextColor(primary)
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = round(surface2, dp(18), outline)
        setPadding(dp(12), dp(7), dp(12), dp(7))
    }
    private fun infoLine(s: String) = TextView(this).apply {
        text = s
        setTextColor(subText)
        setPadding(0, dp(4), 0, dp(4))
        textSize = 13f
    }
    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(1, stroke)
    }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dirtyWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { markDirty() }
        override fun afterTextChanged(s: Editable?) {}
    }
    private fun markDirty() { configDirty = true; updateConfigState() }
    private fun updateConfigState(extra: String = "") {
        val base = if (configDirty) tr("Konfigurasi: belum disimpan, otomatis disimpan saat terhubung", "Config: unsaved, will auto-save on connect") else tr("Konfigurasi: tersimpan", "Config: saved")
        configStateText.text = if (extra.isBlank()) base else "$base · $extra"
    }
    private fun saveInputs() {
        prefs.edit()
            .putString("sni", sniInput.text?.toString().orEmpty())
            .putString("endpoint", normalizedEndpoint())
            .putInt("connectPort", normalizedPort())
            .putBoolean("splitMode", splitModeSwitch.isChecked)
            .putStringSet("selectedPackages", selectedPackages.toSet())
            .apply()
        configDirty = false
        updateConfigState(); updateModeUi()
    }
    private fun loadProfiles() {
        profiles.clear()
        val raw = prefs.getString("profilesJson", "") ?: ""
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                profiles[o.getString("name")] = Triple(o.optString("sni", "www.visa.cn"), o.optString("endpoint", "162.159.198.2"), 443)
            }
        }
        if (profiles.isEmpty()) {
            profiles["Utama 443"] = Triple("www.visa.cn", "162.159.198.2", 443)
            persistProfiles()
        }
        refreshProfileSpinner()
    }
    private fun persistProfiles() {
        val arr = JSONArray()
        profiles.forEach { (name, v) ->
            arr.put(JSONObject().put("name", name).put("sni", v.first).put("endpoint", v.second).put("port", v.third))
        }
        prefs.edit().putString("profilesJson", arr.toString()).apply()
    }
    private fun refreshProfileSpinner() {
        val names = profiles.keys.toList()
        if (::profileSpinner.isInitialized) {
            profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            val current = currentProfileName().takeIf { profiles.containsKey(it) } ?: names.firstOrNull().orEmpty()
            if (current.isNotBlank()) profileSpinner.setSelection(names.indexOf(current).coerceAtLeast(0), false)
            profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val name = names.getOrNull(position) ?: return
                    loadProfileForEditing(name)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        refreshHomeProfileSpinner()
    }
    private fun refreshHomeProfileSpinner() {
        if (!::homeProfileSpinner.isInitialized) return
        val names = profiles.keys.toList()
        suppressProfileSelection = true
        homeProfileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val current = currentProfileName().takeIf { profiles.containsKey(it) } ?: names.firstOrNull().orEmpty()
        if (current.isNotBlank()) homeProfileSpinner.setSelection(names.indexOf(current).coerceAtLeast(0), false)
        suppressProfileSelection = false
        homeProfileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressProfileSelection) return
                val name = names.getOrNull(position) ?: return
                switchHomeProfile(name)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updateCurrentProfileUi()
    }
    private fun selectedProfileName(): String = profileSpinner.selectedItem?.toString().orEmpty()
    private fun currentProfileName(): String = prefs.getString("currentProfileName", "")?.orEmpty() ?: ""
    private fun setCurrentProfileName(name: String) { prefs.edit().putString("currentProfileName", name).apply() }
    private fun updateCurrentProfileUi() {
        if (!::currentProfileText.isInitialized) return
        val name = currentProfileName().takeIf { it.isNotBlank() } ?: profiles.keys.firstOrNull().orEmpty()
        val p = profiles[name]
        currentProfileText.text = if (p != null) {
            tr("Profil saat ini: $name · SNI ${p.first} · ${p.second}:${p.third}", "Current: $name · SNI ${p.first} · ${p.second}:${p.third}")
        } else {
            tr("Profil saat ini: tidak ada", "Current profile: none")
        }
    }
    private fun syncConfigProfileSpinner(name: String) {
        if (!::profileSpinner.isInitialized) return
        val names = profiles.keys.toList()
        val index = names.indexOf(name)
        if (index >= 0) profileSpinner.setSelection(index, false)
    }
    private fun switchHomeProfile(name: String) {
        if (!profiles.containsKey(name)) return
        if (vpnRunning) {
            refreshHomeProfileSpinner()
            toast(tr("Putuskan VPN dulu sebelum mengganti profil", "Disconnect VPN before switching profile"))
            return
        }
        applyProfileToInputs(name, persist = true)
        setCurrentProfileName(name)
        syncConfigProfileSpinner(name)
        updateCurrentProfileUi()
        toast(tr("Profil diganti: $name", "Profile switched: $name"))
    }
    private fun applyProfileToInputs(name: String, persist: Boolean) {
        val p = profiles[name] ?: return
        sniInput.setText(p.first)
        endpointInput.setText(p.second)
        profileNameInput.setText(name)
        if (persist) { markDirty(); saveInputs() }
    }
    private fun saveAsNewProfile() {
        val base = profileNameInput.text?.toString().orEmpty().trim().ifBlank { normalizedEndpoint() }
        val name = uniqueProfileName(base)
        profiles[name] = Triple(sniInput.text?.toString().orEmpty().ifBlank { "www.visa.cn" }, normalizedEndpointHost(), normalizedPort())
        persistProfiles(); refreshProfileSpinner(); profileNameInput.setText(name); syncConfigProfileSpinner(name); toast(tr("Disimpan sebagai profil baru: $name", "Saved as new profile: $name"))
    }
    private fun overwriteSelectedProfile() {
        val selected = selectedProfileName()
        val name = selected.ifBlank { profileNameInput.text?.toString().orEmpty().trim() }
        if (name.isBlank()) return toast(tr("Pilih profil dulu", "Select a profile first"))
        profiles[name] = Triple(sniInput.text?.toString().orEmpty().ifBlank { "www.visa.cn" }, normalizedEndpointHost(), normalizedPort())
        persistProfiles(); refreshProfileSpinner(); profileNameInput.setText(name); syncConfigProfileSpinner(name)
        if (currentProfileName() == name) { setCurrentProfileName(name); refreshHomeProfileSpinner(); updateCurrentProfileUi() }
        toast(tr("Profil saat ini ditimpa: $name", "Current profile overwritten: $name"))
    }
    private fun loadProfileForEditing(name: String) {
        if (!profiles.containsKey(name)) return
        applyProfileToInputs(name, persist = false)
    }
    private fun uniqueProfileName(base: String): String {
        if (!profiles.containsKey(base)) return base
        var index = 2
        while (profiles.containsKey("$base $index")) index++
        return "$base $index"
    }
    private fun deleteSelectedProfile() {
        val name = selectedProfileName()
        if (name.isBlank()) return
        profiles.remove(name)
        if (currentProfileName() == name) setCurrentProfileName(profiles.keys.firstOrNull().orEmpty())
        persistProfiles(); refreshProfileSpinner(); toast(tr("Dihapus: $name", "Deleted: $name"))
    }

    private fun loadSavedState() {
        loadProfiles()
        val saved = prefs.getString("endpoint", "162.159.198.2:443") ?: "162.159.198.2:443"
        endpointInput.setText(parseEndpointHost(saved))
        sniInput.setText(prefs.getString("sni", "www.visa.cn") ?: "www.visa.cn")
        selectedPackages.clear(); selectedPackages.addAll(prefs.getStringSet("selectedPackages", emptySet()) ?: emptySet())
        splitModeSwitch.isChecked = prefs.getBoolean("splitMode", false)
        if (currentProfileName().isBlank() && profiles.isNotEmpty()) setCurrentProfileName(profiles.keys.first())
        configDirty = false
        updateConfigState(); updateModeUi(); refreshHomeProfileSpinner(); updateCurrentProfileUi()
    }

    private fun ensureAppsLoaded() {
        if (appsLoaded) return
        appsLoaded = true
        appCountText.text = tr("Memuat daftar aplikasi…", "Loading app list…")
        loadInstalledApps()
    }
    private fun loadInstalledApps() {
        executor.execute {
            val pm = packageManager
            val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            val apps = pm.getInstalledPackages(flags)
                .asSequence()
                .mapNotNull { pkg -> pkg.applicationInfo }
                .filter { it.packageName != packageName }
                .map { info ->
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
                    AppEntry(label.ifBlank { info.packageName }, info.packageName)
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName })
                .toList()
            handler.post { allApps.clear(); allApps.addAll(apps); refreshAppList() }
        }
    }
    private fun refreshAppList() {
        if (!::appListContainer.isInitialized) return
        if (!appsLoaded) { appCountText.text = tr("Daftar aplikasi dimuat saat membuka Aplikasi", "App list loads when opening Apps"); return }
        appListContainer.removeAllViews()
        val visible = visibleApps()
        visible.forEach { app -> appListContainer.addView(appRow(app), LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(5) }) }
        appCountText.text = if (splitModeSwitch.isChecked) tr("Dipilih ${selectedPackages.size} · Ditampilkan ${visible.size}/${allApps.size}", "Selected ${selectedPackages.size} · Showing ${visible.size}/${allApps.size}") else tr("Mode global: pilihan tidak aktif · Ditampilkan ${visible.size}/${allApps.size}", "Global mode: selections inactive · Showing ${visible.size}/${allApps.size}")
        updateModeUi()
    }
    private fun visibleApps(): List<AppEntry> {
        val query = appSearchInput.text?.toString().orEmpty().trim().lowercase()
        return allApps.asSequence()
            .filter { query.isBlank() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
            .sortedWith(compareByDescending<AppEntry> { selectedPackages.contains(it.packageName) }.thenBy { it.label.lowercase() }.thenBy { it.packageName })
            .toList()
    }
    private fun selectAllVisibleApps() {
        if (!appsLoaded) return
        selectedPackages.addAll(visibleApps().map { it.packageName })
        markDirty(); saveSelectedApps(); refreshAppList()
        toast(tr("Semua aplikasi terlihat dipilih", "Selected all visible apps"))
    }
    private fun clearAllVisibleApps() {
        if (!appsLoaded) return
        selectedPackages.removeAll(visibleApps().map { it.packageName }.toSet())
        markDirty(); saveSelectedApps(); refreshAppList()
        toast(tr("Pilihan aplikasi terlihat dibatalkan", "Cleared visible apps"))
    }

    private fun appRow(app: AppEntry): View = MaterialCheckBox(this).apply {
        text = "${app.label}\n${app.packageName}"
        setTextColor(textColor)
        textSize = 13f
        background = round(if (selectedPackages.contains(app.packageName)) Color.rgb(255, 232, 212) else surface2, dp(18), outline)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        isChecked = selectedPackages.contains(app.packageName)
        setOnCheckedChangeListener { _, checked ->
            if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
            markDirty(); saveSelectedApps(); refreshAppList()
        }
    }
    private fun saveSelectedApps() { prefs.edit().putBoolean("splitMode", splitModeSwitch.isChecked).putStringSet("selectedPackages", selectedPackages.toSet()).apply() }
    private fun updateModeUi() {
        if (!::modeValue.isInitialized) return
        if (splitModeSwitch.isChecked) {
            modeValue.text = tr("Mode Pisah", "Split Mode")
            modeHint.text = tr("Hanya aplikasi yang dipilih di halaman “Aplikasi” yang lewat VPN; aplikasi terpilih tampil paling atas.", "Only selected apps use VPN; selected apps stay on top.")
            if (::appSection.isInitialized) { appSection.visibility = View.VISIBLE; appSection.alpha = 1.0f }
        } else {
            modeValue.text = tr("Mode Global", "Global Mode")
            modeHint.text = tr("Semua aplikasi lewat VPN; pilihan aplikasi tersimpan tapi tidak aktif.", "All apps use VPN; app selections are kept but inactive.")
            if (::appSection.isInitialized) { appSection.visibility = View.VISIBLE; appSection.alpha = 1.0f }
        }
    }
    private fun selectedPackagesForVpn(): ArrayList<String> = ArrayList(selectedPackages)

    private fun hasValidRegistration(): Boolean = configFile.exists() && runCatching { Usqueandroid.isRegistered(configFile.absolutePath) }.getOrDefault(false)
    private fun deleteInvalidConfigIfNeeded() {
        if (!configFile.exists()) return
        val txt = runCatching { configFile.readText() }.getOrDefault("")
        if (txt.contains("\"private_key\": \"\"") || txt.contains("\"access_token\": \"\"") || !hasValidRegistration()) runCatching { configFile.delete() }
    }
    private fun resetSpeedMeter() {
        lastRxBytes = TrafficStats.getTotalRxBytes().takeIf { it >= 0 } ?: 0L
        lastTxBytes = TrafficStats.getTotalTxBytes().takeIf { it >= 0 } ?: 0L
        lastSpeedTs = System.currentTimeMillis()
        if (::speedText.isInitialized) speedText.text = "↓ 0 B/s   ↑ 0 B/s"
    }
    private fun updateSpeedLine() {
        if (!::speedText.isInitialized) return
        if (!vpnRunning) { speedText.text = "↓ 0 B/s   ↑ 0 B/s"; return }
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes().takeIf { it >= 0 } ?: return
        val tx = TrafficStats.getTotalTxBytes().takeIf { it >= 0 } ?: return
        if (lastSpeedTs <= 0L) { resetSpeedMeter(); return }
        val seconds = ((now - lastSpeedTs).coerceAtLeast(1)).toDouble() / 1000.0
        val down = ((rx - lastRxBytes).coerceAtLeast(0) / seconds).toLong()
        val up = ((tx - lastTxBytes).coerceAtLeast(0) / seconds).toLong()
        lastRxBytes = rx; lastTxBytes = tx; lastSpeedTs = now
        speedText.text = "↓ ${formatSpeed(down)}   ↑ ${formatSpeed(up)}"
    }
    private fun formatSpeed(bytesPerSecond: Long): String {
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond.toDouble()
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) { value /= 1024.0; idx++ }
        return if (idx == 0) "${value.toLong()} ${units[idx]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[idx])
    }

    private fun refreshState(extra: String = "") {
        val state = if (vpnRunning) tr("Terhubung", "Connected") else tr("Tidak Terhubung", "Disconnected")
        statusBanner.text = state
        statusText.text = "Status: $state${if (extra.isNotBlank()) " · $extra" else ""}"
        statusBanner.setTextColor(if (vpnRunning) green else onPrimary)
        connectButton.text = if (vpnRunning) tr("Putuskan VPN", "Disconnect VPN") else tr("Hubungkan VPN", "Connect VPN")
        val dark = android.content.res.ColorStateList.valueOf(darkAccent)
        connectButton.backgroundTintList = dark
        connectButton.setTextColor(Color.WHITE)
        if (::languageIdButton.isInitialized) {
            languageIdButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (!useEnglish) darkAccent else primary)
            languageEnButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (useEnglish) darkAccent else primary)
            languageIdButton.setTextColor(if (!useEnglish) Color.WHITE else onPrimary)
            languageEnButton.setTextColor(if (useEnglish) Color.WHITE else onPrimary)
        }
        updateConfigState(extra)
    }
    private fun connectVpn() {
        saveInputs()
        if (vpnRunning) { toast(tr("Sudah berjalan", "Already running")); return }
        if (splitModeSwitch.isChecked && selectedPackages.isEmpty()) { toast(tr("Pilih setidaknya satu aplikasi", "Select at least one app")); log(tr("Di mode pisah, pilih setidaknya satu aplikasi sebelum terhubung", "Select at least one app before connecting in split mode")); refreshState(tr("Tidak ada aplikasi dipilih", "No apps selected")); return }
        if (!hasValidRegistration()) {
            log(tr("File registrasi valid tidak ditemukan. Mendaftar otomatis…", "No valid registration found. Registering automatically…"))
            executor.execute {
                try {
                    deleteInvalidConfigIfNeeded()
                    val result = Usqueandroid.register(configFile.absolutePath, "Android")
                    handler.post {
                        if (result.isNullOrBlank() && hasValidRegistration()) { log(tr("Pendaftaran berhasil. Meminta izin VPN…", "Registered. Requesting VPN permission…")); requestVpnAndStart() }
                        else { log("Pendaftaran gagal: ${result.ifNullOrBlank(tr("Kesalahan tidak diketahui", "Unknown error"))}"); refreshState(tr("Pendaftaran gagal", "Registration failed")) }
                    }
                } catch (e: Exception) { handler.post { log("Galat pendaftaran: ${e.message ?: e.javaClass.simpleName}"); refreshState(tr("Galat pendaftaran", "Registration error")) } }
            }
            return
        }
        requestVpnAndStart()
    }
    private fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null && !vpnGranted) { startActivityForResult(prepareIntent, REQ_VPN); return }
        vpnGranted = true; startTunnelNow()
    }
    private fun startTunnelNow() {
        val sni = sniInput.text?.toString().orEmpty().ifBlank { "www.visa.cn" }
        val endpoint = "${normalizedEndpointHost()}:${normalizedPort()}"
        val splitMode = splitModeSwitch.isChecked
        val allowedApps = if (splitMode) selectedPackagesForVpn() else arrayListOf()
        log(if (splitMode) tr("Memulai VPN pisah: ${allowedApps.size} aplikasi · $endpoint", "Starting split VPN: ${allowedApps.size} apps · $endpoint") else tr("Memulai VPN global: $endpoint", "Starting global VPN: $endpoint"))
        resetSpeedMeter()
        vpnRunning = true; refreshState(tr("Memulai", "Starting"))
        val intent = Intent(this, EmbengVpnService::class.java)
            .putExtra("configPath", configFile.absolutePath)
            .putExtra("sni", sni)
            .putExtra("endpoint", endpoint)
            .putExtra("splitMode", splitMode)
            .putStringArrayListExtra("allowedApps", allowedApps)
        startService(intent)
        log(tr("Layanan VPN dimulai", "VPN service started"))
        refreshState(if (splitMode) tr("Mode pisah berjalan", "Split mode running") else tr("Mode global berjalan", "Global mode running"))
    }
    private fun disconnectVpn() {
        log(tr("Menghentikan layanan VPN…", "Stopping VPN service…"))
        vpnRunning = false; refreshState(tr("Menghentikan", "Stopping")); resetSpeedMeter()
        EmbengVpnService.stopActiveTunnel()
        runCatching { startService(Intent(this, EmbengVpnService::class.java).setAction(EmbengVpnService.ACTION_STOP)) }
        handler.postDelayed({ runCatching { stopService(Intent(this, EmbengVpnService::class.java)) }; onTunnelStopped(tr("Dihentikan", "Stopped")) }, 500)
    }
    private fun onTunnelStopped(msg: String) { vpnRunning = false; refreshState(msg); log(msg) }
    private fun normalizedEndpointHost(): String = parseEndpointHost(endpointInput.text?.toString().orEmpty().trim().ifBlank { "162.159.198.2" }).ifBlank { "162.159.198.2" }
    private fun normalizedPort(): Int = 443
    private fun normalizedEndpoint(): String = "${normalizedEndpointHost()}:${normalizedPort()}"
    private fun parseEndpointHost(value: String): String { val v = value.trim(); if (v.isBlank()) return "162.159.198.2"; if (v.startsWith("[") && v.contains("]")) return v.substringAfter("[").substringBefore("]"); return if (v.count { it == ':' } == 1) v.substringBefore(':') else v }
    private fun log(msg: String) { logText.text = msg }
    private fun String?.ifNullOrBlank(fallback: String): String = if (this.isNullOrBlank()) fallback else this
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) { vpnGranted = true; if (hasValidRegistration()) startTunnelNow() else connectVpn() }
        else if (requestCode == REQ_VPN) toast(tr("Izin VPN ditolak", "VPN permission denied"))
    }
}
