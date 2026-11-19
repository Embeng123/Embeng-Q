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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.divider.MaterialDivider
import usqueandroid.Usqueandroid
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQ_VPN = 1001
        private const val DEFAULT_SNI = "www.visa.cn"
        private val SNI_PRESETS = listOf("contoh.com", "bb.com", "hh.com")
    }
    data class AppEntry(val label: String, val packageName: String)
    private data class Profile(val sni: String, val endpoint: String, val customSni: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("embengq", MODE_PRIVATE) }
    private val configFile by lazy { File(filesDir, "config.json") }

    private lateinit var endpointInput: TextInputEditText
    private lateinit var sniDropdown: MaterialAutoCompleteTextView
    private lateinit var sniInput: TextInputEditText
    private lateinit var customSniField: TextInputLayout
    private lateinit var statusBanner: TextView
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
    private lateinit var homeProfileSpinner: Spinner
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
    private var configDirty = false
    private var appsLoaded = false
    private var editingProfileName = ""
    private var sniChoice = SNI_PRESETS.size
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
    private val profiles = linkedMapOf<String, Profile>()
    private val allApps = mutableListOf<AppEntry>()
    private val selectedPackages = linkedSetOf<String>()

    private val bg = Color.rgb(244, 246, 250)
    private val surface = Color.WHITE
    private val surface2 = Color.rgb(240, 244, 250)
    private val primary = Color.rgb(226, 236, 255)
    private val darkAccent = Color.rgb(36, 84, 176)
    private val onPrimary = Color.rgb(24, 61, 133)
    private val textColor = Color.rgb(23, 34, 51)
    private val subText = Color.rgb(77, 91, 113)
    private val outline = Color.rgb(215, 223, 235)
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
        val choice = sniChoice
        val custom = sniInput.text?.toString().orEmpty()
        val endpoint = endpointInput.text?.toString().orEmpty()
        val profileName = profileNameInput.text?.toString().orEmpty()
        val dirty = configDirty
        useEnglish = en
        prefs.edit().putBoolean("useEnglish", useEnglish).apply()
        buildUi()
        loadSavedState()
        sniInput.setText(custom)
        showSniChoice(choice)
        endpointInput.setText(endpoint)
        profileNameInput.setText(profileName)
        configDirty = dirty
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
            setTitleTextColor(textColor)
            setBackgroundColor(surface)
            setPadding(dp(4), 0, dp(4), 0)
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(8))
            setBackgroundColor(surface)
        }
        val pageHost = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(MaterialDivider(this).apply { dividerColor = outline })
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        fun pageScroll(child: View): ScrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            (child.parent as? ViewGroup)?.removeView(child)
            addView(child)
        }
        val overviewTab = tabButton(tr("Beranda", "Home"))
        val configTab = tabButton(tr("Pengaturan", "Settings"))
        val appsTab = tabButton(tr("Aplikasi", "Apps"))
        val tabsList = listOf(overviewTab, configTab, appsTab)
        tabsList.forEachIndexed { index, b ->
            tabs.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index < 2) rightMargin = dp(4) })
        }

        val homePage = buildHomePage()
        val configPage = buildConfigPage()
        val appsPage = buildAppsPage()
        val pages = listOf(homePage, configPage, appsPage)
        fun showIndex(index: Int) {
            val safe = index.coerceIn(0, pages.lastIndex)
            currentPageIndex = safe
            tabsList.forEachIndexed { i, b -> setTabSelected(b, i == safe) }
            if (safe == 2) ensureAppsLoaded()
            pageHost.removeAllViews()
            pageHost.addView(pageScroll(pages[safe]), FrameLayout.LayoutParams(-1, -1))
        }

        overviewTab.setOnClickListener { showIndex(0) }
        configTab.setOnClickListener { showIndex(1) }
        appsTab.setOnClickListener { showIndex(2) }
        pageSwitcher = ::showIndex
        showIndex(currentPageIndex)

        defaultBtn.setOnClickListener {
            val defaultEndpoint = Usqueandroid.getDefaultEndpoint(configFile.absolutePath)
            endpointInput.setText(parseEndpointHost(defaultEndpoint))
            refreshState(tr("Endpoint default dimuat", "Default endpoint loaded"))
        }
        saveNewProfileBtn.setOnClickListener { saveAsNewProfile() }
        overwriteProfileBtn.setOnClickListener { overwriteSelectedProfile() }
        deleteProfileBtn.setOnClickListener { deleteSelectedProfile() }
        connectButton.setOnClickListener { if (vpnRunning) disconnectVpn() else connectVpn() }
        sniInput.addTextChangedListener(dirtyWatcher { customSniField.error = null })
        endpointInput.addTextChangedListener(dirtyWatcher())
        appSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshAppList() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun buildHomePage(): View {
        val content = pageContent()
        val hero = card()
        val heroBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        statusBanner = TextView(this).apply {
            textSize = 24f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusText = infoLine("")
        speedText = TextView(this).apply {
            text = "↓ 0 B/s   ↑ 0 B/s"
            textSize = 14f
            setTextColor(subText)
            setTypeface(null, Typeface.BOLD)
        }
        homeProfileSpinner = Spinner(this)
        connectButton = MaterialButton(this).apply {
            text = tr("Hubungkan VPN", "Connect VPN")
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(darkAccent)
            cornerRadius = dp(10)
            minimumHeight = dp(52)
            insetTop = 0
            insetBottom = 0
            isAllCaps = false
        }
        heroBox.addView(statusBanner)
        heroBox.addView(statusText)
        heroBox.addView(speedText)
        heroBox.addView(connectButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        hero.addView(heroBox)
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))

        val profileLabel = sectionTitle(tr("Profil VPN", "VPN profile"))
        homeProfileSpinner.id = View.generateViewId()
        profileLabel.labelFor = homeProfileSpinner.id
        content.addView(profileLabel)
        val profileCard = card()
        profileCard.addView(homeProfileSpinner, FrameLayout.LayoutParams(-1, dp(48)))
        content.addView(profileCard)

        content.addView(sectionTitle(tr("Akses VPN", "VPN access")))
        val modeCard = card()
        val modeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        modeHint = infoLine("")
        splitModeSwitch = MaterialSwitch(this).apply {
            text = tr("Hanya aplikasi pilihan", "Selected apps only")
            textSize = 15f
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

    private fun sniOptions() = SNI_PRESETS + tr("Custom (isi sendiri)", "Custom (enter your own)")

    private fun showSniChoice(choice: Int) {
        sniChoice = choice
        sniDropdown.setText(sniOptions()[choice], false)
        customSniField.visibility = if (choice == SNI_PRESETS.size) View.VISIBLE else View.GONE
        customSniField.error = null
    }

    private fun restoreSni(value: String, useCustom: Boolean = false) {
        val preset = SNI_PRESETS.indexOf(value)
        val choice = if (useCustom || preset < 0) SNI_PRESETS.size else preset
        sniInput.setText(if (choice == SNI_PRESETS.size) value else "")
        showSniChoice(choice)
    }

    private fun selectedSni(): String = SNI_PRESETS.getOrNull(sniChoice) ?: sniInput.text?.toString().orEmpty().trim()
    private fun isCustomSni(): Boolean = sniChoice == SNI_PRESETS.size

    private fun requireSni(): String? {
        val value = selectedSni()
        if (value.isNotBlank()) return value
        pageSwitcher?.invoke(1)
        customSniField.error = tr("Masukkan domain SNI", "Enter an SNI domain")
        sniInput.requestFocus()
        return null
    }

    private fun buildConfigPage(): View {
        val content = pageContent()

        val config = card()
        val configBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        sniDropdown = MaterialAutoCompleteTextView(this).apply {
            keyListener = null
            setTextColor(textColor)
            setSimpleItems(sniOptions().toTypedArray())
            setOnItemClickListener { _, _, position, _ ->
                showSniChoice(position)
                markDirty()
            }
        }
        val sniField = outlinedField("SNI", sniDropdown).apply {
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        sniInput = input("contoh.com").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        customSniField = outlinedField(tr("Domain SNI", "SNI domain"), sniInput)
        showSniChoice(SNI_PRESETS.size)
        endpointInput = input("162.159.198.2").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        defaultBtn = secondaryButton(tr("Gunakan alamat bawaan", "Use default address"))
        configStateText = infoLine("")
        configBox.addView(sniField)
        configBox.addView(customSniField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        configBox.addView(outlinedField(tr("Alamat server (IP)", "Server address (IP)"), endpointInput),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        configBox.addView(defaultBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        configBox.addView(configStateText)
        config.addView(configBox)
        content.addView(config)

        content.addView(sectionTitle(tr("Profil tersimpan", "Saved profiles")))
        val profileCard = card()
        val profileBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(14)) }
        profileSpinner = Spinner(this).apply { contentDescription = tr("Pilih profil untuk diedit", "Select a profile to edit") }
        profileNameInput = input("")
        saveNewProfileBtn = secondaryButton(tr("Simpan profil baru", "Save new profile"))
        overwriteProfileBtn = secondaryButton(tr("Perbarui profil pilihan", "Update selected profile"))
        deleteProfileBtn = secondaryButton(tr("Hapus profil pilihan", "Delete selected profile"))
        profileBox.addView(profileSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        profileBox.addView(outlinedField(tr("Nama profil", "Profile name"), profileNameInput))
        profileBox.addView(saveNewProfileBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        profileBox.addView(overwriteProfileBtn)
        profileBox.addView(deleteProfileBtn)
        profileCard.addView(profileBox)
        content.addView(profileCard)

        content.addView(sectionTitle(tr("Bahasa", "Language")))
        val languageBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        languageIdButton = secondaryButton("Indonesia").apply { setOnClickListener { setLanguage(false) } }
        languageEnButton = secondaryButton("English").apply { setOnClickListener { setLanguage(true) } }
        languageBox.addView(languageIdButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        languageBox.addView(languageEnButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(languageBox)
        return content
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
        setPadding(dp(16), dp(16), dp(16), dp(24))
        setBackgroundColor(bg)
    }

    private fun tabButton(label: String) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        cornerRadius = dp(10)
        minimumHeight = dp(48)
        setPadding(dp(2), 0, dp(2), 0)
        insetTop = 0
        insetBottom = 0
        setTextColor(subText)
        backgroundTintList = ColorStateList.valueOf(surface)
    }

    private fun setTabSelected(button: MaterialButton, selected: Boolean) {
        button.setTextColor(if (selected) onPrimary else subText)
        button.backgroundTintList = ColorStateList.valueOf(if (selected) primary else surface)
        button.isSelected = selected
    }

    private fun sectionHeader(title: String, desc: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), 0, dp(2), dp(8))
        addView(TextView(this@MainActivity).apply { text = title; textSize = 20f; setTextColor(textColor); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@MainActivity).apply { text = desc; textSize = 12f; setTextColor(subText); setPadding(0, dp(2), 0, 0) })
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
        textSize = 14f
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
        textSize = 14f
        setTextColor(subText)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(2), dp(18), dp(2), dp(8))
    }
    private fun input(hint: String) = TextInputEditText(this).apply {
        setHint(hint)
        setTextColor(textColor)
        setHintTextColor(subText)
        isSingleLine = true
    }
    private fun outlinedField(label: String, edit: EditText) = TextInputLayout(this).apply {
        hint = label
        placeholderText = edit.hint
        edit.hint = null
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = surface
        val corner = dp(8).toFloat()
        setBoxCornerRadii(corner, corner, corner, corner)
        boxStrokeColor = darkAccent
        edit.minimumHeight = dp(56)
        edit.setPaddingRelative(dp(14), dp(12), dp(14), dp(12))
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
            .putBoolean("sniCustom", isCustomSni())
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
                val sni = o.optString("sni", DEFAULT_SNI).trim().ifBlank { DEFAULT_SNI }
                profiles[o.getString("name")] = Profile(
                    sni,
                    o.optString("endpoint", "162.159.198.2"),
                    o.optBoolean("sniCustom", sni !in SNI_PRESETS)
                )
            }
        }
        if (profiles.isEmpty()) {
            profiles["Utama 443"] = Profile(DEFAULT_SNI, "162.159.198.2", customSni = true)
            persistProfiles()
        }
        if (!profiles.containsKey(currentProfileName())) setCurrentProfileName(profiles.keys.first())
        refreshProfileSpinner(editingProfileName)
    }
    private fun persistProfiles() {
        val arr = JSONArray()
        profiles.forEach { (name, profile) ->
            arr.put(JSONObject().put("name", name).put("sni", profile.sni).put("endpoint", profile.endpoint)
                .put("port", normalizedPort()).put("sniCustom", profile.customSni))
        }
        prefs.edit().putString("profilesJson", arr.toString()).apply()
    }
    private fun refreshProfileSpinner(preferredName: String = editingProfileName) {
        val names = profiles.keys.toList()
        editingProfileName = preferredName.takeIf { it in profiles }
            ?: currentProfileName().takeIf { it in profiles } ?: names.firstOrNull().orEmpty()
        setupProfileSpinner(profileSpinner, names, editingProfileName) { name ->
            if (name != editingProfileName) {
                editingProfileName = name
                applyProfileToInputs(name, persist = false)
            }
        }
        refreshHomeProfileSpinner()
    }
    private fun refreshHomeProfileSpinner() {
        setupProfileSpinner(homeProfileSpinner, profiles.keys.toList(), currentProfileName()) { name ->
            if (name == currentProfileName()) return@setupProfileSpinner
            if (vpnRunning) {
                homeProfileSpinner.setSelection(profiles.keys.indexOf(currentProfileName()), false)
                toast(tr("Putuskan VPN dulu sebelum mengganti profil", "Disconnect VPN before switching profile"))
            } else {
                switchHomeProfile(name)
            }
        }
    }
    private fun setupProfileSpinner(spinner: Spinner, names: List<String>, selected: String, onSelect: (String) -> Unit) {
        spinner.onItemSelectedListener = null
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val index = names.indexOf(selected)
        if (index >= 0) spinner.setSelection(index, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                names.getOrNull(position)?.let(onSelect)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    private fun selectedProfileName(): String = profileSpinner.selectedItem?.toString().orEmpty()
    private fun currentProfileName(): String = prefs.getString("currentProfileName", "")?.orEmpty() ?: ""
    private fun setCurrentProfileName(name: String) { prefs.edit().putString("currentProfileName", name).apply() }
    private fun switchHomeProfile(name: String) {
        applyProfileToInputs(name, persist = true)
        setCurrentProfileName(name)
        editingProfileName = name
        profileSpinner.setSelection(profiles.keys.indexOf(name), false)
        toast(tr("Profil diganti: $name", "Profile switched: $name"))
    }
    private fun applyProfileToInputs(name: String, persist: Boolean) {
        val p = profiles[name] ?: return
        restoreSni(p.sni, p.customSni)
        endpointInput.setText(p.endpoint)
        profileNameInput.setText(name)
        if (persist) { markDirty(); saveInputs() }
    }
    private fun saveAsNewProfile() {
        val sni = requireSni() ?: return
        val base = profileNameInput.text?.toString().orEmpty().trim().ifBlank { normalizedEndpoint() }
        val name = uniqueProfileName(base)
        profiles[name] = Profile(sni, normalizedEndpointHost(), isCustomSni())
        if (currentProfileName().isBlank()) setCurrentProfileName(name)
        persistProfiles()
        refreshProfileSpinner(name)
        profileNameInput.setText(name)
        toast(tr("Disimpan sebagai profil baru: $name", "Saved as new profile: $name"))
    }
    private fun overwriteSelectedProfile() {
        val selected = selectedProfileName()
        val name = selected.ifBlank { profileNameInput.text?.toString().orEmpty().trim() }
        if (name.isBlank()) return toast(tr("Pilih profil dulu", "Select a profile first"))
        val sni = requireSni() ?: return
        profiles[name] = Profile(sni, normalizedEndpointHost(), isCustomSni())
        persistProfiles()
        refreshProfileSpinner(name)
        profileNameInput.setText(name)
        toast(tr("Profil saat ini ditimpa: $name", "Current profile overwritten: $name"))
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
        persistProfiles()
        refreshProfileSpinner()
        profileNameInput.setText(editingProfileName)
        toast(tr("Dihapus: $name", "Deleted: $name"))
    }

    private fun loadSavedState() {
        loadProfiles()
        val saved = prefs.getString("endpoint", "162.159.198.2:443") ?: "162.159.198.2:443"
        endpointInput.setText(parseEndpointHost(saved))
        val savedSni = prefs.getString("sni", DEFAULT_SNI).orEmpty().trim().ifBlank { DEFAULT_SNI }
        restoreSni(savedSni, prefs.getBoolean("sniCustom", false))
        profileNameInput.setText(editingProfileName)
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
