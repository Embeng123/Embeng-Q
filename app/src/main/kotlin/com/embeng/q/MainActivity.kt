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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.content.res.AppCompatResources
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class MainActivity : Activity() {
    companion object {
        private const val REQ_VPN = 1001
        private const val DEFAULT_SNI = "classroom.google.com"
        private val accountRegistrationBusy = AtomicBoolean(false)
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
    private lateinit var accountStatusText: TextView
    private lateinit var accountDetailsText: TextView
    private lateinit var accountButton: MaterialButton
    private var accountMessage = ""
    private var accountDetailsVersion: Pair<Long, Long>? = null
    private var accountDetails: String? = null
    private lateinit var logText: TextView
    private lateinit var modeHint: TextView
    private lateinit var splitModeSwitch: MaterialSwitch
    private lateinit var appSearchInput: TextInputEditText
    private lateinit var appListContainer: ListView
    private lateinit var appListAdapter: BaseAdapter
    private val displayedApps = mutableListOf<AppEntry>()
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
    private val speedMeter = TunnelSpeedMeter()
    private var lastTunnelSnapshot = TunnelSnapshot()
    private val tunnelObserver: (TunnelSnapshot) -> Unit = { snapshot ->
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                if (snapshot.phase != lastTunnelSnapshot.phase || snapshot.interfaceName != lastTunnelSnapshot.interfaceName) {
                    resetSpeedMeter()
                }
                lastTunnelSnapshot = snapshot
                refreshState()
            }
        }
    }
    private val speedTicker = object : Runnable {
        override fun run() {
            updateSpeedLine()
            handler.postDelayed(this, 1000)
        }
    }
    private val allApps = mutableListOf<AppEntry>()
    private val selectedPackages = linkedSetOf<String>()

    private val bg = Color.rgb(18, 18, 18)
    private val navy = Color.rgb(33, 33, 33)
    private val cyan = Color.WHITE
    private val surface = Color.rgb(30, 30, 30)
    private val surface2 = Color.rgb(42, 42, 42)
    private val primary = Color.rgb(56, 56, 56)
    private val darkAccent = Color.rgb(224, 224, 224)
    private val onPrimary = Color.WHITE
    private val textColor = Color.rgb(245, 245, 245)
    private val subText = Color.rgb(189, 189, 189)
    private val outline = Color.rgb(66, 66, 66)
    private val green = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Embeng-Q"
        useEnglish = prefs.getBoolean("useEnglish", false)
        buildUi()
        loadSavedState()
        refreshAppList()
        refreshState()
        resetSpeedMeter()
        if (!hasValidRegistration() && !accountRegistrationBusy.get()) registerAccount()
    }

    override fun onStart() {
        super.onStart()
        TunnelState.observe(tunnelObserver)
        resetSpeedMeter()
        handler.post(speedTicker)
    }

    override fun onStop() {
        TunnelState.removeObserver(tunnelObserver)
        handler.removeCallbacks(speedTicker)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateAccountUi()
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
            setPadding(dp(16), 0, dp(8), 0)
        }
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_brand_shield)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(26)).apply { rightMargin = dp(10) })
        toolbar.addView(TextView(this).apply {
            text = SpannableString("Embeng-Q").apply {
                setSpan(ForegroundColorSpan(cyan), 6, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 24f
            typeface = brandTypeface
            letterSpacing = -0.025f
            setTextColor(Color.WHITE)
            contentDescription = "Embeng-Q"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        languageButton = secondaryButton(if (useEnglish) "EN ▾" else "ID ▾").apply {
            contentDescription = tr("Pilih bahasa. Saat ini Bahasa Indonesia", "Choose language. Currently English")
            setTextColor(cyan)
            backgroundTintList = ColorStateList.valueOf(navy)
            textSize = 12f
            cornerRadius = dp(16)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(outline)
            setPadding(dp(8), 0, dp(8), 0)
            minimumWidth = 0
            insetTop = dp(8)
            insetBottom = dp(8)
            setOnClickListener {
                PopupMenu(this@MainActivity, this).apply {
                    menu.add(0, 0, 0, "Bahasa Indonesia").isChecked = !useEnglish
                    menu.add(0, 1, 1, "English").isChecked = useEnglish
                    menu.setGroupCheckable(0, true, true)
                    setOnMenuItemClickListener { item -> setLanguage(item.itemId == 1); true }
                    show()
                }
            }
        }
        toolbar.addView(languageButton, LinearLayout.LayoutParams(dp(64), dp(48)))
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(navy)
        }
        val pageHost = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
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
            tabs.addView(b, LinearLayout.LayoutParams(dp(104), -1).apply { if (index < tabsList.lastIndex) rightMargin = dp(4) })
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
            val page = if (safe == 0) pageScroll(pages[safe]) else pages[safe].apply {
                (parent as? ViewGroup)?.removeView(this)
            }
            pageHost.addView(page, FrameLayout.LayoutParams(-1, -1))
        }

        overviewTab.setOnClickListener { showIndex(0) }
        appsTab.setOnClickListener { showIndex(1) }
        pageSwitcher = ::showIndex
        showIndex(currentPageIndex)

        defaultBtn.setOnClickListener {
            val defaultEndpoint = runCatching { Usqueandroid.getDefaultEndpoint(configFile.absolutePath) }
                .getOrDefault("162.159.198.2:443")
            sniInput.setText(DEFAULT_SNI)
            endpointInput.setText(parseEndpointHost(defaultEndpoint))
            refreshState(tr("SNI dan server bawaan dipulihkan", "Default SNI and server restored"))
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
        content.addView(buildConnectionSettings())
        val accountCard = card()
        val accountBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        accountStatusText = infoLine("")
        accountDetailsText = infoLine("").apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        accountButton = secondaryButton(tr("Buat ulang akun", "Create new account")).apply {
            backgroundTintList = ColorStateList.valueOf(surface)
            setOnClickListener {
                if (TunnelState.snapshot.isActive || Usqueandroid.isRunning()) {
                    toast(tr("Putuskan VPN sebelum mengganti akun", "Disconnect VPN before replacing the account"))
                } else if (hasValidRegistration()) {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(tr("Buat ulang akun WARP?", "Create a new WARP account?"))
                        .setMessage(tr("Akun lama akan diganti setelah pendaftaran baru berhasil.", "The existing account will be replaced after registration succeeds."))
                        .setNegativeButton(tr("Batal", "Cancel"), null)
                        .setPositiveButton(tr("Buat ulang", "Create new")) { _, _ -> registerAccount(replace = true) }
                        .show()
                } else registerAccount()
            }
        }
        accountBox.addView(accountStatusText)
        accountBox.addView(accountDetailsText)
        accountBox.addView(accountButton, LinearLayout.LayoutParams(-2, dp(48)).apply { gravity = Gravity.END })
        accountCard.addView(accountBox)

        content.addView(sectionTitle(tr("AKUN WARP", "WARP ACCOUNT")))
        content.addView(accountCard)
        logText = TextView(this)
        return content
    }

    private fun buildConnectionBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minimumHeight = dp(76)
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
            setPadding(0, dp(3), 0, 0)
        }
        connectButton = MaterialButton(this).apply {
            text = ""
            contentDescription = tr("Hubungkan VPN", "Connect VPN")
            icon = AppCompatResources.getDrawable(this@MainActivity, R.drawable.ic_power)
            iconSize = dp(24)
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconTint = ColorStateList.valueOf(bg)
            gravity = Gravity.CENTER
            backgroundTintList = ColorStateList.valueOf(darkAccent)
            cornerRadius = dp(28)
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
        bar.addView(connectButton, LinearLayout.LayoutParams(dp(56), dp(56)))
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
            setPadding(dp(12), dp(12), dp(12), dp(4))
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
        configBox.addView(connectionHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        sniInput = input(DEFAULT_SNI).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        sniField = outlinedField("SNI", sniInput)
        endpointInput = input("162.159.198.2").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        defaultBtn = secondaryButton(tr("Pulihkan bawaan", "Reset defaults")).apply {
            backgroundTintList = ColorStateList.valueOf(surface)
            textSize = 12f
        }
        configStateText = infoLine("")
        configBox.addView(outlinedField(tr("Alamat server (IP)", "Server address (IP)"), endpointInput))
        configBox.addView(sniField,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        configBox.addView(defaultBtn, LinearLayout.LayoutParams(-2, dp(48)).apply { gravity = Gravity.END })
        configBox.addView(configStateText)
        config.addView(configBox)
        return config
    }

    private fun buildAppsPage(): View {
        val content = pageContent()
        splitModeSwitch = MaterialSwitch(this).apply {
            text = tr("Hanya aplikasi yang dipilih", "Only selected apps")
            textSize = 13f
            setTextColor(textColor)
            minimumHeight = dp(48)
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, _ ->
                markDirty(); saveSelectedApps(); refreshAppList()
            }
        }
        modeHint = infoLine("").apply { textSize = 12f }
        content.addView(splitModeSwitch)
        content.addView(modeHint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        appSearchInput = input(tr("Nama aplikasi atau paket", "App name or package"))
        appCountText = infoLine(tr("0 dipilih", "0 selected")).apply { textSize = 12f; maxLines = 2 }
        content.addView(outlinedField(tr("Cari aplikasi", "Search apps"), appSearchInput))
        val appActions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        appActions.addView(appCountText, LinearLayout.LayoutParams(0, -2, 1f))
        selectAllAppsBtn = secondaryButton(tr("Semua", "All")).apply {
            textSize = 12f
            backgroundTintList = ColorStateList.valueOf(bg)
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { selectAllVisibleApps() }
        }
        clearAllAppsBtn = secondaryButton(tr("Batal", "None")).apply {
            textSize = 12f
            backgroundTintList = ColorStateList.valueOf(bg)
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { clearAllVisibleApps() }
        }
        appActions.addView(selectAllAppsBtn, LinearLayout.LayoutParams(-2, dp(48)))
        appActions.addView(clearAllAppsBtn, LinearLayout.LayoutParams(-2, dp(48)))
        content.addView(appActions)
        appListAdapter = object : BaseAdapter() {
            override fun getCount() = displayedApps.size
            override fun getItem(position: Int) = displayedApps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                appRow(getItem(position), convertView as? MaterialCheckBox)
        }
        appListContainer = ListView(this).apply {
            adapter = appListAdapter
            divider = android.graphics.drawable.ColorDrawable(outline)
            dividerHeight = dp(1)
            setBackgroundColor(surface)
        }
        content.addView(appListContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        return content
    }

    private fun pageContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
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
        setTextColor(subText)
        backgroundTintList = ColorStateList.valueOf(navy)
        rippleColor = ColorStateList.valueOf(outline)
    }

    private fun setTabSelected(button: MaterialButton, selected: Boolean) {
        button.setTextColor(if (selected) cyan else subText)
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

    private fun card() = MaterialCardView(this).apply {
        radius = dp(6).toFloat()
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
        cornerRadius = dp(4)
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
        setPadding(dp(2), dp(12), dp(2), dp(6))
    }
    private fun input(hint: String) = TextInputEditText(this).apply {
        setHint(hint)
        setTextColor(textColor)
        setHintTextColor(subText)
        textSize = 15f
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
        edit.minimumHeight = dp(56)
        edit.setPaddingRelative(dp(12), dp(20), dp(12), dp(6))
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
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val savedPackages = prefs.getStringSet("selectedPackages", emptySet()).orEmpty().toList()
            val launchable = pm.queryIntentActivities(launcher, PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { it.activityInfo.applicationInfo }
            val savedApps = savedPackages.mapNotNull { pkg ->
                runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            }
            val apps = (launchable + savedApps).asSequence()
                .filter { it.packageName != packageName }
                .map { info ->
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
                    AppEntry(label.ifBlank { info.packageName }, info.packageName)
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName })
                .toList()
            handler.post {
                if (!isDestroyed && !isFinishing) { allApps.clear(); allApps.addAll(apps); refreshAppList() }
            }
        }
    }
    private fun refreshAppList() {
        if (!::appListContainer.isInitialized) return
        if (!appsLoaded) { appCountText.text = tr("Daftar aplikasi dimuat saat membuka Aplikasi", "App list loads when opening Apps"); return }
        val visible = visibleApps()
        displayedApps.clear()
        displayedApps.addAll(visible)
        appListAdapter.notifyDataSetChanged()
        appCountText.text = if (splitModeSwitch.isChecked)
            tr("${selectedPackages.size} dipilih · ${visible.size}/${allApps.size}", "${selectedPackages.size} selected · ${visible.size}/${allApps.size}")
            else tr("Semua aplikasi", "All apps")
        updateModeUi()
    }
    private fun visibleApps(): List<AppEntry> {
        val query = appSearchInput.text?.toString().orEmpty().trim().lowercase()
        return allApps.asSequence()
            .filter { query.isBlank() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
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

    private fun appRow(app: AppEntry, recycled: MaterialCheckBox? = null): View = (recycled ?: MaterialCheckBox(this)).apply {
        setOnCheckedChangeListener(null)
        text = SpannableString("${app.label}\n${app.packageName}").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, app.label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = app.label.length + 1
            setSpan(ForegroundColorSpan(subText), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.82f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setTextColor(textColor)
        textSize = 12f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        minimumHeight = dp(48)
        setBackgroundColor(if (selectedPackages.contains(app.packageName)) primary else surface)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        isChecked = selectedPackages.contains(app.packageName)
        isEnabled = splitModeSwitch.isChecked
        setOnCheckedChangeListener { _, checked ->
            if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
            markDirty(); saveSelectedApps(); refreshAppList()
        }
    }
    private fun saveSelectedApps() { prefs.edit().putBoolean("splitMode", splitModeSwitch.isChecked).putStringSet("selectedPackages", selectedPackages.toSet()).apply() }
    private fun updateModeUi() {
        if (!::modeHint.isInitialized) {
            return
        }
        val selectedOnly = splitModeSwitch.isChecked
        modeHint.text = if (selectedOnly) {
            if (selectedPackages.isEmpty()) tr("Centang minimal satu aplikasi. Hanya yang dicentang memakai VPN.",
                "Check at least one app. Only checked apps use the VPN.")
            else tr("Yang dicentang memakai VPN; aplikasi lain memakai internet biasa.",
                "Checked apps use the VPN; other apps use your regular internet.")
        } else {
            tr("Semua aplikasi memakai VPN. Aktifkan untuk memilih aplikasi tertentu.",
                "All apps use the VPN. Turn on to choose specific apps.")
        }
        if (::selectAllAppsBtn.isInitialized) selectAllAppsBtn.isEnabled = selectedOnly
        if (::clearAllAppsBtn.isInitialized) clearAllAppsBtn.isEnabled = selectedOnly
        if (TunnelState.snapshot.isActive) {
            val hint = tr(" Hubungkan ulang VPN setelah mengubah pilihan.", " Reconnect the VPN after changing your selection.")
            modeHint.append(hint)
        }
    }
    private fun selectedPackagesForVpn(): ArrayList<String> = ArrayList(selectedPackages.filter { it != packageName })

    private fun hasValidRegistration(): Boolean = configFile.exists() && runCatching { Usqueandroid.isRegistered(configFile.absolutePath) }.getOrDefault(false)
    private fun updateAccountDetails(valid: Boolean) {
        if (!valid) {
            accountDetailsVersion = null
            accountDetails = null
        } else {
            val version = configFile.lastModified() to configFile.length()
            if (version != accountDetailsVersion) {
                accountDetails = runCatching {
                    JSONObject(configFile.readText()).toString(2)
                }.getOrNull()
                accountDetailsVersion = if (accountDetails != null) version else null
            }
        }
        val details = accountDetails
        accountDetailsText.visibility = if (details == null) View.GONE else View.VISIBLE
        accountDetailsText.text = details.orEmpty()
    }
    private fun updateAccountUi() {
        val busy = accountRegistrationBusy.get()
        val valid = hasValidRegistration()
        accountStatusText.text = when {
            busy -> tr("Mendaftarkan akun WARP…", "Registering WARP account…")
            accountMessage.isNotBlank() -> accountMessage
            valid -> tr("Akun WARP siap digunakan", "WARP account is ready")
            else -> tr("Belum ada akun. Hubungkan internet lalu coba daftar.", "No account yet. Connect to the internet and try registering.")
        }
        updateAccountDetails(valid)
        accountButton.text = if (valid) tr("Buat ulang akun", "Create new account") else tr("Coba daftar akun", "Try registering")
        accountButton.isEnabled = !busy && !TunnelState.snapshot.isActive
        connectButton.isEnabled = !busy && TunnelState.snapshot.phase != TunnelPhase.STOPPING
    }
    private fun registerAccount(replace: Boolean = false, connectAfter: Boolean = false) {
        if (TunnelState.snapshot.isActive) {
            toast(tr("Putuskan VPN sebelum mengganti akun", "Disconnect VPN before replacing the account"))
            return
        }
        if (!accountRegistrationBusy.compareAndSet(false, true)) {
            toast(tr("Pendaftaran masih berjalan", "Registration is still in progress"))
            return
        }
        accountMessage = ""
        updateAccountUi()
        executor.execute {
            val pending = File(filesDir, "registration-pending.json")
            val outcome = runCatching {
                if (replace || !hasValidRegistration()) {
                    check(!pending.exists() || pending.delete()) { "Cannot clear pending registration" }
                    val result = Usqueandroid.register(pending.absolutePath, "Android")
                    check(result.isNullOrBlank()) { result.orEmpty() }
                    check(pending.exists() && Usqueandroid.isRegistered(pending.absolutePath)) {
                        "Registration returned an invalid account"
                    }
                    check(pending.renameTo(configFile)) { "Cannot save registered account" }
                }
            }
            pending.delete()
            accountRegistrationBusy.set(false)
            handler.post {
                if (isFinishing || isDestroyed) {
                    return@post
                }
                accountDetailsVersion = null
                accountMessage = if (outcome.isSuccess) {
                    tr("Akun WARP siap digunakan", "WARP account is ready")
                } else {
                    tr("Pendaftaran gagal. Periksa internet lalu coba lagi.", "Registration failed. Check your internet connection and retry.") +
                        if (hasValidRegistration()) tr(" Akun lama tetap tersedia.", " The existing account is still available.") else ""
                }
                updateAccountUi()
                if (outcome.isSuccess && connectAfter) requestVpnAndStart()
                if (outcome.isFailure) {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(tr("Pendaftaran akun gagal", "Account registration failed"))
                        .setMessage(outcome.exceptionOrNull()?.message ?: tr("Kesalahan tidak diketahui", "Unknown error"))
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
    private fun resetSpeedMeter() {
        speedMeter.reset()
        if (::speedText.isInitialized) speedText.text = "↓ 0 B/s   ↑ 0 B/s"
    }
    private fun readTunnelTraffic(interfaceName: String?): TrafficSample? {
        if (interfaceName == null || !interfaceName.matches(Regex("[a-zA-Z0-9_.-]+"))) return null
        return runCatching {
            val rx: Long
            val tx: Long
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                rx = TrafficStats.getRxBytes(interfaceName)
                tx = TrafficStats.getTxBytes(interfaceName)
            } else {
                val stats = File("/sys/class/net/$interfaceName/statistics")
                rx = File(stats, "rx_bytes").readText().trim().toLong()
                tx = File(stats, "tx_bytes").readText().trim().toLong()
            }
            if (rx < 0 || tx < 0) null else TrafficSample(rx, tx, SystemClock.elapsedRealtime())
        }.getOrNull()
    }
    private fun updateSpeedLine() {
        updateAccountUi()
        if (!::speedText.isInitialized) return
        val snapshot = TunnelState.snapshot
        if (snapshot.phase != TunnelPhase.CONNECTED) { resetSpeedMeter(); return }
        val rate = speedMeter.update(readTunnelTraffic(snapshot.interfaceName))
        speedText.text = if (rate == null) tr("Kecepatan VPN tidak tersedia", "VPN speed unavailable")
            else "↓ ${formatSpeed(rate.download)}   ↑ ${formatSpeed(rate.upload)}"
    }
    private fun formatSpeed(bytesPerSecond: Long): String {
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond.toDouble()
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) { value /= 1024.0; idx++ }
        return if (idx == 0) "${value.toLong()} ${units[idx]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[idx])
    }

    private fun refreshState(extra: String = "") {
        val snapshot = TunnelState.snapshot
        vpnRunning = snapshot.isActive
        val connected = snapshot.phase == TunnelPhase.CONNECTED
        val state = when (snapshot.phase) {
            TunnelPhase.CONNECTING -> tr("Menghubungkan", "Connecting")
            TunnelPhase.CONNECTED -> tr("Terhubung", "Connected")
            TunnelPhase.RECONNECTING -> tr("Menyambung ulang", "Reconnecting")
            TunnelPhase.STOPPING -> tr("Memutuskan", "Disconnecting")
            TunnelPhase.DISCONNECTED -> tr("Tidak Terhubung", "Disconnected")
        }
        statusBanner.text = state
        val detail = extra.ifBlank { snapshot.detail }
        statusText.text = detail
        statusText.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        statusBanner.setTextColor(if (connected) green else textColor)
        statusIndicator.setTextColor(if (connected) green else subText)
        connectButton.contentDescription = if (vpnRunning) tr("Putuskan VPN", "Disconnect VPN") else tr("Hubungkan VPN", "Connect VPN")
        connectButton.backgroundTintList = ColorStateList.valueOf(if (vpnRunning) navy else darkAccent)
        connectButton.iconTint = ColorStateList.valueOf(if (vpnRunning) Color.WHITE else bg)
        updateAccountUi()
        updateConfigState()
    }
    private fun connectVpn() {
        if (accountRegistrationBusy.get()) {
            toast(tr("Pendaftaran masih berjalan", "Registration is still in progress"))
            return
        }
        if (requireSni() == null) return
        saveInputs()
        if (TunnelState.snapshot.isActive) { toast(tr("Sudah berjalan", "Already running")); return }
        if (splitModeSwitch.isChecked && selectedPackagesForVpn().isEmpty()) { toast(tr("Pilih setidaknya satu aplikasi", "Select at least one app")); log(tr("Di mode pisah, pilih setidaknya satu aplikasi sebelum terhubung", "Select at least one app before connecting in split mode")); refreshState(tr("Tidak ada aplikasi dipilih", "No apps selected")); return }
        if (!hasValidRegistration()) {
            registerAccount(connectAfter = true)
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
        val intent = Intent(this, EmbengVpnService::class.java)
            .putExtra("configPath", configFile.absolutePath)
            .putExtra("sni", sni)
            .putExtra("endpoint", endpoint)
            .putExtra("splitMode", splitMode)
            .putStringArrayListExtra("allowedApps", allowedApps)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            refreshState(tr("Memulai layanan VPN…", "Starting VPN service…"))
        } catch (e: Exception) {
            refreshState(e.message ?: tr("Gagal memulai VPN", "Could not start VPN"))
        }
    }
    private fun disconnectVpn() {
        log(tr("Menghentikan layanan VPN…", "Stopping VPN service…"))
        EmbengVpnService.stopActiveTunnel()
        refreshState()
        resetSpeedMeter()
    }
    private fun normalizedEndpointHost(): String = parseEndpointHost(endpointInput.text?.toString().orEmpty().trim().ifBlank { "162.159.198.2" }).ifBlank { "162.159.198.2" }
    private fun normalizedPort(): Int = 443
    private fun normalizedEndpoint(): String {
        val host = normalizedEndpointHost()
        return if (':' in host) "[$host]:${normalizedPort()}" else "$host:${normalizedPort()}"
    }
    private fun parseEndpointHost(value: String): String { val v = value.trim(); if (v.isBlank()) return "162.159.198.2"; if (v.startsWith("[") && v.contains("]")) return v.substringAfter("[").substringBefore("]"); return if (v.count { it == ':' } == 1) v.substringBefore(':') else v }
    private fun log(msg: String) { logText.text = msg }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) { if (hasValidRegistration()) requestVpnAndStart() else connectVpn() }
        else if (requestCode == REQ_VPN) toast(tr("Izin VPN ditolak", "VPN permission denied"))
    }
}
