package com.embeng.q

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.divider.MaterialDivider
import usqueandroid.Usqueandroid
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQ_VPN = 1001
        private const val DEFAULT_SNI = "classroom.google.com"
    }
    data class AppEntry(val label: String, val packageName: String)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("embengq", MODE_PRIVATE) }
    private val configFile by lazy { File(filesDir, "config.json") }
    private val brandTypeface by lazy { Typeface.createFromAsset(assets, "fonts/Rajdhani-Bold.ttf") }

    private lateinit var endpointInput: TextInputEditText
    private lateinit var sniInput: TextInputEditText
    private lateinit var sniField: TextInputLayout
    private lateinit var statusBanner: TextView
    private lateinit var statusIndicator: TextView
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var configStateText: TextView
    private lateinit var logText: TextView
    private lateinit var modeHint: TextView
    private lateinit var splitModeSwitch: MaterialSwitch
    private lateinit var appSearchInput: TextInputEditText
    private lateinit var appListContainer: LinearLayout
    private lateinit var appCountText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var defaultBtn: MaterialButton
    private lateinit var languageButton: MaterialButton
    private lateinit var selectAllAppsBtn: MaterialButton
    private lateinit var clearAllAppsBtn: MaterialButton

    private var useEnglish = false
    private var vpnRunning = false
    private var configDirty = false
    private var appsLoaded = false
    private var currentPageIndex = 0
    private var pageSwitcher: ((Int) -> Unit)? = null
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSpeedTs = 0L
    private val speedTicker = object : Runnable {
        override fun run() {
            updateSpeedLine()
            handler.postDelayed(this, 1000)
        }
    }
    private val allApps = mutableListOf<AppEntry>()
    private val selectedPackages = linkedSetOf<String>()

    private val bg = Color.rgb(245, 247, 249)
    private val navy = Color.rgb(8, 24, 39)
    private val cyan = Color.rgb(63, 225, 232)
    private val surface = Color.WHITE
    private val surface2 = Color.rgb(241, 246, 247)
    private val primary = Color.rgb(221, 245, 245)
    private val darkAccent = Color.rgb(0, 116, 124)
    private val onPrimary = Color.rgb(0, 85, 91)
    private val textColor = Color.rgb(23, 34, 51)
    private val subText = Color.rgb(77, 91, 113)
    private val outline = Color.rgb(226, 232, 236)
    private val green = Color.rgb(23, 110, 66)

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
        executor.shutdown()
        super.onDestroy()
    }

    private fun tr(id: String, en: String): String = if (useEnglish) en else id
    private fun setLanguage(en: Boolean) {
        if (en == useEnglish) return
        val sni = sniInput.text?.toString().orEmpty()
        val endpoint = endpointInput.text?.toString().orEmpty()
        val dirty = configDirty
        useEnglish = en
        prefs.edit().putBoolean("useEnglish", useEnglish).apply()
        buildUi()
        loadSavedState()
        sniInput.setText(sni)
        endpointInput.setText(endpoint)
        configDirty = dirty
        refreshAppList()
        refreshState()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(20), 0, dp(12), 0)
        }
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_brand_shield)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(30), dp(34)).apply { rightMargin = dp(10) })
        toolbar.addView(TextView(this).apply {
            text = SpannableString("Embeng-Q").apply {
                setSpan(ForegroundColorSpan(cyan), 6, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 32f
            typeface = brandTypeface
            letterSpacing = -0.025f
            setTextColor(Color.WHITE)
            contentDescription = "Embeng-Q"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        languageButton = secondaryButton(if (useEnglish) "ID" else "EN").apply {
            contentDescription = tr("Ganti ke bahasa Inggris", "Switch to Indonesian")
            setTextColor(cyan)
            backgroundTintList = ColorStateList.valueOf(navy)
            setOnClickListener { setLanguage(!useEnglish) }
        }
        toolbar.addView(languageButton, LinearLayout.LayoutParams(dp(56), dp(48)))
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(navy)
        }
        val pageHost = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(68)))
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))

        fun pageScroll(child: View): ScrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            (child.parent as? ViewGroup)?.removeView(child)
            addView(child)
        }
        val overviewTab = tabButton(tr("Beranda", "Home"))
        val appsTab = tabButton(tr("Aplikasi", "Apps"))
        val tabsList = listOf(overviewTab, appsTab)
        tabsList.forEachIndexed { index, b ->
            tabs.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index < tabsList.lastIndex) rightMargin = dp(4) })
        }

        val homePage = buildHomePage()
        val appsPage = buildAppsPage()
        root.addView(MaterialDivider(this).apply { dividerColor = outline })
        root.addView(buildConnectionBar(), LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        val pages = listOf(homePage, appsPage)
        fun showIndex(index: Int) {
            val safe = index.coerceIn(0, pages.lastIndex)
            currentPageIndex = safe
            tabsList.forEachIndexed { i, b -> setTabSelected(b, i == safe) }
            if (safe == 1) ensureAppsLoaded()
            pageHost.removeAllViews()
            pageHost.addView(pageScroll(pages[safe]), FrameLayout.LayoutParams(-1, -1))
        }

        overviewTab.setOnClickListener { showIndex(0) }
        appsTab.setOnClickListener { showIndex(1) }
        pageSwitcher = ::showIndex
        showIndex(currentPageIndex)

        defaultBtn.setOnClickListener {
            val defaultEndpoint = Usqueandroid.getDefaultEndpoint(configFile.absolutePath)
            endpointInput.setText(parseEndpointHost(defaultEndpoint))
            refreshState(tr("Endpoint default dimuat", "Default endpoint loaded"))
        }
        connectButton.setOnClickListener { if (vpnRunning) disconnectVpn() else connectVpn() }
        sniInput.addTextChangedListener(dirtyWatcher { sniField.error = null })
        endpointInput.addTextChangedListener(dirtyWatcher())
        appSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshAppList() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun buildHomePage(): View {
        val content = pageContent()
        content.addView(sectionHeader(tr("Koneksi", "Connection"), tr("Edit SNI, lalu hubungkan.", "Edit your SNI, then connect.")))
        content.addView(buildConnectionSettings())

        content.addView(sectionTitle(tr("PERUTEAN", "ROUTING")))
        val modeCard = card()
        val modeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12)) }
        modeHint = infoLine("")
        splitModeSwitch = MaterialSwitch(this).apply {
            text = tr("Hanya aplikasi pilihan", "Selected apps only")
            textSize = 14f
            setTextColor(textColor)
            minimumHeight = dp(48)
            setOnCheckedChangeListener { _, _ ->
                markDirty(); saveSelectedApps(); updateModeUi(); refreshAppList()
            }
        }
        modeBox.addView(splitModeSwitch)
        modeBox.addView(modeHint)
        modeCard.addView(modeBox)
        content.addView(modeCard)
        logText = TextView(this)
        return content
    }

    private fun buildConnectionBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            minimumHeight = dp(96)
        }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusIndicator = TextView(this).apply { text = "●"; textSize = 10f; setTextColor(subText) }
        statusBanner = TextView(this).apply {
            textSize = 14f
            setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusRow.addView(statusIndicator, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        statusRow.addView(statusBanner)
        statusText = infoLine("").apply { textSize = 11f; maxLines = 2 }
        speedText = TextView(this).apply {
            text = "↓ 0 B/s   ↑ 0 B/s"
            textSize = 12f
            setTextColor(subText)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
        }
        connectButton = MaterialButton(this).apply {
            text = ""
            contentDescription = tr("Hubungkan VPN", "Connect VPN")
            icon = getDrawable(R.drawable.ic_power)
            iconSize = dp(28)
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconTint = ColorStateList.valueOf(Color.WHITE)
            gravity = Gravity.CENTER
            backgroundTintList = ColorStateList.valueOf(darkAccent)
            cornerRadius = dp(32)
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            insetTop = 0
            insetBottom = 0
            elevation = dp(3).toFloat()
        }
        details.addView(statusRow)
        details.addView(speedText)
        details.addView(statusText)
        bar.addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(16) })
        bar.addView(connectButton, LinearLayout.LayoutParams(dp(64), dp(64)))
        return bar
    }

    private fun selectedSni(): String = sniInput.text?.toString().orEmpty().trim()

    private fun requireSni(): String? {
        val value = selectedSni()
        if (value.isNotBlank()) return value
        pageSwitcher?.invoke(0)
        sniField.error = tr("Masukkan domain SNI", "Enter an SNI domain")
        sniInput.requestFocus()
        return null
    }

    private fun buildConnectionSettings(): View {
        val config = card()
        val configBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        val connectionHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        connectionHeader.addView(TextView(this).apply {
            text = "WARP / MASQUE"
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(darkAccent)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        connectionHeader.addView(TextView(this).apply {
            text = "443"
            textSize = 11f
            setTextColor(subText)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = round(surface2, dp(4))
        })
        configBox.addView(connectionHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        sniInput = input(DEFAULT_SNI).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        sniField = outlinedField("SNI", sniInput)
        endpointInput = input("162.159.198.2").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        defaultBtn = secondaryButton(tr("Pulihkan server bawaan", "Reset server address")).apply {
            backgroundTintList = ColorStateList.valueOf(surface)
            textSize = 12f
        }
        configStateText = infoLine("")
        configBox.addView(sniField)
        configBox.addView(outlinedField(tr("Alamat server (IP)", "Server address (IP)"), endpointInput),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        configBox.addView(defaultBtn, LinearLayout.LayoutParams(-2, dp(48)).apply { gravity = Gravity.END })
        configBox.addView(configStateText)
        config.addView(configBox)
        return config
    }

    private fun buildAppsPage(): View {
        val content = pageContent()
        content.addView(sectionHeader(tr("Pilih aplikasi", "Select apps"), tr("Aktifkan ‘Hanya aplikasi pilihan’ di Beranda untuk memakai daftar ini.", "Turn on ‘Selected apps only’ on Home to use this list.")))
        val appSection = card()
        val appBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        appSearchInput = input(tr("Nama aplikasi atau paket", "App name or package"))
        appCountText = infoLine(tr("0 aplikasi dipilih", "0 apps selected"))
        appListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appBox.addView(outlinedField(tr("Cari aplikasi", "Search apps"), appSearchInput))
        appBox.addView(appCountText)
        val appActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectAllAppsBtn = secondaryButton(tr("Pilih Semua", "Select All")).apply { setOnClickListener { selectAllVisibleApps() } }
        clearAllAppsBtn = secondaryButton(tr("Batalkan Semua", "Select None")).apply { setOnClickListener { clearAllVisibleApps() } }
        appActions.addView(selectAllAppsBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(8) })
        appActions.addView(clearAllAppsBtn, LinearLayout.LayoutParams(0, -1, 1f))
        appBox.addView(appActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        appBox.addView(MaterialDivider(this).apply { dividerColor = outline }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(8); bottomMargin = dp(8) })
        appBox.addView(appListContainer)
        appSection.addView(appBox)
        content.addView(appSection)
        return content
    }

    private fun pageContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(22), dp(20), dp(24))
        setBackgroundColor(bg)
    }

    private fun tabButton(label: String) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        cornerRadius = 0
        minimumHeight = dp(48)
        setPadding(dp(2), 0, dp(2), 0)
        insetTop = 0
        insetBottom = 0
        setTextColor(Color.rgb(155, 177, 191))
        backgroundTintList = ColorStateList.valueOf(navy)
        rippleColor = ColorStateList.valueOf(Color.rgb(24, 60, 70))
    }

    private fun setTabSelected(button: MaterialButton, selected: Boolean) {
        button.setTextColor(if (selected) cyan else Color.rgb(155, 177, 191))
        button.backgroundTintList = ColorStateList.valueOf(navy)
        val indicator = if (selected) GradientDrawable().apply {
            setColor(cyan)
            setBounds(0, 0, dp(32), dp(2))
        } else null
        button.setCompoundDrawables(null, null, null, indicator)
        button.compoundDrawablePadding = dp(6)
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.isSelected = selected
    }

    private fun sectionHeader(title: String, desc: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(20))
        addView(TextView(this@MainActivity).apply { text = title; textSize = 22f; setTextColor(textColor); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        addView(TextView(this@MainActivity).apply { text = desc; textSize = 13f; setTextColor(subText); setPadding(0, dp(4), 0, 0) })
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(12).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = outline
        setCardBackgroundColor(surface)
        useCompatPadding = false
    }
    private fun secondaryButton(label: String) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        cornerRadius = dp(10)
        minimumHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        setTextColor(onPrimary)
        backgroundTintList = ColorStateList.valueOf(surface2)
        strokeWidth = 0
    }
    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s
        textSize = 11f
        letterSpacing = 0.1f
        setTextColor(subText)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(2), dp(18), dp(2), dp(8))
    }
    private fun input(hint: String) = TextInputEditText(this).apply {
        setHint(hint)
        setTextColor(textColor)
        setHintTextColor(subText)
        textSize = 17f
        isSingleLine = true
    }
    private fun outlinedField(label: String, edit: EditText) = TextInputLayout(this).apply {
        hint = label
        placeholderText = edit.hint
        edit.hint = null
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
        boxBackgroundColor = surface2
        val corner = dp(6).toFloat()
        setBoxCornerRadii(corner, corner, corner, corner)
        boxStrokeColor = darkAccent
        edit.minimumHeight = dp(64)
        edit.setPaddingRelative(dp(14), dp(24), dp(14), dp(8))
        addView(edit, LinearLayout.LayoutParams(-1, -2))
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
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dirtyWatcher(onChange: () -> Unit = {}) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChange()
            markDirty()
        }
        override fun afterTextChanged(s: Editable?) {}
    }
    private fun markDirty() { configDirty = true; updateConfigState() }
    private fun updateConfigState() {
        configStateText.text = tr("Perubahan dipakai saat VPN dihubungkan.", "Changes apply when you connect the VPN.")
        configStateText.visibility = if (configDirty) View.VISIBLE else View.GONE
    }
    private fun saveInputs() {
        prefs.edit()
            .putString("sni", selectedSni())
            .putString("endpoint", normalizedEndpoint())
            .putInt("connectPort", normalizedPort())
            .putBoolean("splitMode", splitModeSwitch.isChecked)
            .putStringSet("selectedPackages", selectedPackages.toSet())
            .apply()
        configDirty = false
        updateConfigState(); updateModeUi()
    }
    private fun loadSavedState() {
        val saved = prefs.getString("endpoint", "162.159.198.2:443") ?: "162.159.198.2:443"
        endpointInput.setText(parseEndpointHost(saved))
        var savedSni = prefs.getString("sni", DEFAULT_SNI).orEmpty().trim().ifBlank { DEFAULT_SNI }
        if (!prefs.getBoolean("directSniMigrated", false)) {
            if (savedSni == "www.visa.cn") savedSni = DEFAULT_SNI
            prefs.edit().putString("sni", savedSni).putBoolean("directSniMigrated", true).apply()
        }
        sniInput.setText(savedSni)
        selectedPackages.clear(); selectedPackages.addAll(prefs.getStringSet("selectedPackages", emptySet()) ?: emptySet())
        splitModeSwitch.isChecked = prefs.getBoolean("splitMode", false)
        configDirty = false
        updateConfigState(); updateModeUi()
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
            val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
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
        visible.forEach { app -> appListContainer.addView(appRow(app), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) }) }
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
        minimumHeight = dp(52)
        background = round(if (selectedPackages.contains(app.packageName)) primary else surface2, dp(8), outline)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        isChecked = selectedPackages.contains(app.packageName)
        setOnCheckedChangeListener { _, checked ->
            if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
            markDirty(); saveSelectedApps(); refreshAppList()
        }
    }
    private fun saveSelectedApps() { prefs.edit().putBoolean("splitMode", splitModeSwitch.isChecked).putStringSet("selectedPackages", selectedPackages.toSet()).apply() }
    private fun updateModeUi() {
        if (!::modeHint.isInitialized) return
        if (splitModeSwitch.isChecked) {
            modeHint.text = tr("Atur daftar di tab Aplikasi.", "Manage the list in the Apps tab.")
        } else {
            modeHint.text = tr("Semua aplikasi memakai VPN.", "All apps use the VPN.")
        }
    }
    private fun selectedPackagesForVpn(): ArrayList<String> = ArrayList(selectedPackages.filter { it != packageName })

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
        statusText.text = extra
        statusText.visibility = if (extra.isBlank()) View.GONE else View.VISIBLE
        statusBanner.setTextColor(if (vpnRunning) green else textColor)
        statusIndicator.setTextColor(if (vpnRunning) green else subText)
        connectButton.contentDescription = if (vpnRunning) tr("Putuskan VPN", "Disconnect VPN") else tr("Hubungkan VPN", "Connect VPN")
        connectButton.backgroundTintList = ColorStateList.valueOf(if (vpnRunning) navy else darkAccent)
        updateConfigState()
    }
    private fun connectVpn() {
        if (requireSni() == null) return
        saveInputs()
        if (vpnRunning) { toast(tr("Sudah berjalan", "Already running")); return }
        if (splitModeSwitch.isChecked && selectedPackagesForVpn().isEmpty()) { toast(tr("Pilih setidaknya satu aplikasi", "Select at least one app")); log(tr("Di mode pisah, pilih setidaknya satu aplikasi sebelum terhubung", "Select at least one app before connecting in split mode")); refreshState(tr("Tidak ada aplikasi dipilih", "No apps selected")); return }
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
        if (prepareIntent != null) { startActivityForResult(prepareIntent, REQ_VPN); return }
        startTunnelNow()
    }
    private fun startTunnelNow() {
        val sni = requireSni() ?: return
        saveInputs()
        val endpoint = normalizedEndpoint()
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
    private fun normalizedEndpoint(): String {
        val host = normalizedEndpointHost()
        return if (':' in host) "[$host]:${normalizedPort()}" else "$host:${normalizedPort()}"
    }
    private fun parseEndpointHost(value: String): String { val v = value.trim(); if (v.isBlank()) return "162.159.198.2"; if (v.startsWith("[") && v.contains("]")) return v.substringAfter("[").substringBefore("]"); return if (v.count { it == ':' } == 1) v.substringBefore(':') else v }
    private fun log(msg: String) { logText.text = msg }
    private fun String?.ifNullOrBlank(fallback: String): String = if (this.isNullOrBlank()) fallback else this
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) { if (hasValidRegistration()) requestVpnAndStart() else connectVpn() }
        else if (requestCode == REQ_VPN) toast(tr("Izin VPN ditolak", "VPN permission denied"))
    }
}
