package com.example.esp32telemetry

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity(), BleTelemetryClient.Listener {
    private enum class AppTab(val label: String) {
        Connection("Connect"),
        Telemetry("Telemetry"),
        Commands("Commands"),
        Log("Log"),
    }

    private data class UiColors(
        val background: Int,
        val surface: Int,
        val surfaceStrong: Int,
        val text: Int,
        val muted: Int,
        val accent: Int,
        val accentText: Int,
        val border: Int,
    )

    private lateinit var bleClient: BleTelemetryClient
    private val parser = TelemetryParser()
    private val reducer = TelemetryReducer()
    private val state = TelemetryState()
    private val devices = linkedMapOf<String, BleDeviceInfo>()
    private val fieldViews = mutableMapOf<String, TextView>()
    private val rawEntries = ArrayDeque<Pair<Long, String>>()
    private val pendingBleChunks = ArrayDeque<ByteArray>()
    private val pendingBleLock = Any()
    private val rawRetentionMs = 5 * 60 * 1000L
    private val fieldRefreshMs = 100L
    private val timestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val rtcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd,HH:mm:ss")
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            applyParsedLines(parser.flushStalePartial())
            refreshFields()
            uiHandler.postDelayed(this, fieldRefreshMs)
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var contentHost: LinearLayout
    private lateinit var deviceList: LinearLayout
    private lateinit var commandEntry: EditText
    private lateinit var rawLog: TextView
    private lateinit var disconnectButton: Button
    private var commandButtons: List<Button> = emptyList()
    private var writeDependentViews: List<View> = emptyList()
    private var activeTab = AppTab.Connection
    private var darkMode = false
    private var activeCanWrite = false
    private var bleDrainPosted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = getPreferences(MODE_PRIVATE).getBoolean("dark_mode", false)
        bleClient = BleTelemetryClient(this, this)
        buildUi()
        requestBlePermissions()
        refreshFields()
        uiHandler.post(refreshTick)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(refreshTick)
        bleClient.shutdown()
        super.onDestroy()
    }

    private fun buildUi() {
        val colors = colors()
        applySystemBars(colors)
        fieldViews.clear()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            val sidePadding = if (isCompactWidth()) dp(10) else dp(16)
            setPadding(sidePadding, topInset() + dp(12), sidePadding, dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = if (isVeryCompactWidth()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (isVeryCompactWidth()) Gravity.START else Gravity.CENTER_VERTICAL
        }
        statusView = TextView(this).apply {
            text = if (state.connected) "Connected to ${state.deviceName}" else "Disconnected"
            textSize = if (isCompactWidth()) 16f else 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.text)
            maxLines = 3
            layoutParams = if (isVeryCompactWidth()) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        val themeButton = Button(this).apply {
            text = if (darkMode) "Light" else "Dark"
            setOnClickListener {
                darkMode = !darkMode
                getPreferences(MODE_PRIVATE).edit().putBoolean("dark_mode", darkMode).apply()
                buildUi()
                refreshFields()
            }
        }
        styleButton(themeButton, colors, filled = false)
        header.addView(statusView)
        header.addView(themeButton, headerButtonParams())
        root.addView(header)

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        AppTab.entries.forEach { tab ->
            val button = Button(this).apply {
                text = tab.label
                setOnClickListener {
                    activeTab = tab
                    renderCurrentTab()
                }
            }
            tabRow.addView(button, tabButtonParams())
        }
        root.addView(HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        })

        contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(contentHost)
        setContentView(root)
        renderCurrentTab()
    }

    private fun renderCurrentTab() {
        val colors = colors()
        for (index in 0 until tabRow.childCount) {
            val button = tabRow.getChildAt(index) as Button
            styleButton(button, colors, filled = AppTab.entries[index] == activeTab)
        }

        contentHost.removeAllViews()
        when (activeTab) {
            AppTab.Connection -> renderConnectionTab(colors)
            AppTab.Telemetry -> renderTelemetryTab(colors)
            AppTab.Commands -> renderCommandsTab(colors)
            AppTab.Log -> renderLogTab(colors)
        }
        setConnectedControls(state.connected, activeCanWrite)
        refreshFields()
    }

    private fun renderConnectionTab(colors: UiColors) {
        val body = verticalBody(colors)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scanButton = Button(this).apply {
            text = "Scan"
            setOnClickListener {
                devices.clear()
                renderDeviceButtons(colors)
                bleClient.scan()
            }
        }
        disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { bleClient.disconnect() }
        }
        styleButton(scanButton, colors, filled = true)
        styleButton(disconnectButton, colors, filled = false)
        controls.addView(scanButton, actionButtonParams(first = true))
        controls.addView(disconnectButton, actionButtonParams(first = false))
        body.addView(controls)

        body.addView(sectionLabel("Devices", colors))
        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(deviceList)
        renderDeviceButtons(colors)
        contentHost.addView(scroll(body))
    }

    private fun renderTelemetryTab(colors: UiColors) {
        val body = verticalBody(colors)
        val fields = listOf(
            "Connection" to "connection",
            "Last update time" to "last_update_time",
            "Seconds since last update" to "seconds_since_last_update",
            "CAN RX count" to "can_rx_count",
            "Last CAN ID" to "last_can_id",
            "Last CAN data" to "last_can_data",
            "GPS status" to "gps_status",
            "GPS speed" to "gps_speed",
            "Latest GPS sentence" to "latest_gps_sentence",
            "IMU speed" to "imu_speed",
            "Temperature / pressure / humidity" to "environment",
            "SD card status" to "sd_card_status",
            "STM32 alive" to "stm32_alive",
            "ESP32 alive" to "esp32_alive",
            "Last response" to "last_response",
        )
        fields.forEach { (label, key) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(colors.surface, colors.border)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(colors.muted)
            })
            val value = TextView(this).apply {
                text = "-"
                textSize = 16f
                setTextColor(colors.text)
                setPadding(0, dp(4), 0, 0)
                setSingleLine(false)
            }
            fieldViews[key] = value
            row.addView(value)
            body.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            })
        }
        contentHost.addView(scroll(body))
    }

    private fun renderCommandsTab(colors: UiColors) {
        val body = verticalBody(colors)
        val commandGrid = GridLayout(this).apply {
            columnCount = commandColumnCount()
            useDefaultMargins = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        commandButtons = (defaultCommands.map { spec ->
            Button(this).apply {
                text = spec.label
                setOnClickListener { sendCommand(spec.payload) }
            }
        } + listOf(
            Button(this).apply {
                text = "SET_RTC,now"
                setOnClickListener { sendCommand("SET_RTC,${LocalDateTime.now().format(rtcFormatter)}") }
            },
        ))
        commandButtons.forEach {
            styleButton(it, colors, filled = true)
            commandGrid.addView(it, commandButtonParams())
        }
        body.addView(commandGrid)

        val customRow = LinearLayout(this).apply {
            orientation = if (isCompactWidth()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (isCompactWidth()) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        commandEntry = EditText(this).apply {
            hint = "Custom command"
            setSingleLine(true)
            setTextColor(colors.text)
            setHintTextColor(colors.muted)
            background = rounded(colors.surface, colors.border)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = commandEntryParams()
        }
        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val command = commandEntry.text.toString().trim()
                if (command.isNotEmpty()) {
                    sendCommand(command)
                    commandEntry.setText("")
                }
            }
        }
        styleButton(sendButton, colors, filled = false)
        customRow.addView(commandEntry)
        customRow.addView(sendButton, sendButtonParams())
        body.addView(customRow)

        writeDependentViews = commandButtons + listOf(commandEntry, sendButton)
        contentHost.addView(scroll(body))
    }

    private fun renderLogTab(colors: UiColors) {
        rawLog = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(colors.text)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(colors.surfaceStrong, colors.border)
            text = rawEntries.joinToString("\n") { it.second }
        }
        contentHost.addView(scroll(rawLog))
    }

    private fun renderDeviceButtons(colors: UiColors = colors()) {
        if (!::deviceList.isInitialized) return
        deviceList.removeAllViews()
        if (devices.isEmpty()) {
            deviceList.addView(TextView(this).apply {
                text = "No devices yet. Tap Scan to search."
                textSize = 15f
                setTextColor(colors.muted)
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        devices.values.forEach { device ->
            val button = Button(this).apply {
                text = "${device.name} [${device.address}]"
                setOnClickListener { bleClient.connect(device.device) }
            }
            styleButton(button, colors, filled = false)
            deviceList.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54),
            ).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
                100,
            )
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        }
    }

    private fun sendCommand(command: String) {
        appendRaw("> $command")
        bleClient.send(command)
    }

    override fun onDeviceFound(device: BleDeviceInfo) = runOnUiThread {
        if (devices.containsKey(device.address)) return@runOnUiThread
        devices[device.address] = device
        if (activeTab == AppTab.Connection) renderDeviceButtons()
    }

    override fun onStatus(message: String) = runOnUiThread {
        statusView.text = message
    }

    override fun onLog(message: String) = runOnUiThread {
        appendRaw(message)
    }

    override fun onConnected(name: String, address: String, canWrite: Boolean) = runOnUiThread {
        state.connected = true
        state.deviceName = "$name [$address]"
        activeCanWrite = canWrite
        statusView.text = "Connected to ${state.deviceName}"
        appendRaw("! connected to ${state.deviceName}")
        if (!canWrite) appendRaw("! no writable characteristic found; receive-only mode")
        setConnectedControls(connected = true, canWrite = canWrite)
        refreshFields()
    }

    override fun onDisconnected(expected: Boolean) = runOnUiThread {
        state.connected = false
        activeCanWrite = false
        statusView.text = if (expected) "Disconnected" else "Disconnected - reconnecting"
        appendRaw(if (expected) "! disconnected" else "! disconnected unexpectedly")
        setConnectedControls(connected = false, canWrite = false)
        refreshFields()
    }

    override fun onData(data: ByteArray) {
        synchronized(pendingBleLock) {
            pendingBleChunks.addLast(data)
            if (bleDrainPosted) return
            bleDrainPosted = true
        }
        uiHandler.postDelayed({ drainBleChunks() }, 25)
    }

    private fun drainBleChunks() {
        val chunks = mutableListOf<ByteArray>()
        synchronized(pendingBleLock) {
            while (pendingBleChunks.isNotEmpty()) {
                chunks += pendingBleChunks.removeFirst()
            }
            bleDrainPosted = false
        }
        if (chunks.isEmpty()) return

        val combined = ByteArrayOutputStream(chunks.sumOf { it.size })
        chunks.forEach { combined.write(it) }
        applyParsedLines(parser.feed(combined.toByteArray()))
        refreshFields()
    }

    override fun onCommandSent(command: String) = runOnUiThread {
        appendRaw("> $command (BLE write complete)")
    }

    override fun onError(message: String) = runOnUiThread {
        appendRaw("! $message")
        statusView.text = message
    }

    private fun setConnectedControls(connected: Boolean, canWrite: Boolean) {
        if (::disconnectButton.isInitialized) disconnectButton.isEnabled = connected
        writeDependentViews.forEach { it.isEnabled = connected && canWrite }
    }

    private fun refreshFields() {
        val now = SystemClock.elapsedRealtime()
        val connection = if (state.connected) "Connected - ${state.deviceName}" else "Disconnected"
        val environment = "${state.temperature} / ${state.pressure} / ${state.humidity}"
        val values = mapOf(
            "connection" to connection,
            "last_update_time" to state.lastUpdateTime,
            "seconds_since_last_update" to state.secondsSinceLastUpdate(now),
            "can_rx_count" to state.canRxCount.toString(),
            "last_can_id" to state.lastCanId,
            "last_can_data" to state.lastCanData,
            "gps_status" to state.gpsStatus,
            "gps_speed" to state.gpsSpeed,
            "latest_gps_sentence" to state.latestGpsSentence,
            "imu_speed" to state.imuSpeed,
            "environment" to environment,
            "sd_card_status" to state.sdCardStatus,
            "stm32_alive" to state.stm32Alive,
            "esp32_alive" to state.esp32Alive,
            "last_response" to state.lastResponse,
        )
        values.forEach { (key, value) -> fieldViews[key]?.text = value }
    }

    private fun appendRaw(line: String) {
        val now = SystemClock.elapsedRealtime()
        val timestamp = LocalDateTime.now().format(timestampFormatter)
        rawEntries.addLast(now to "[$timestamp] $line")
        while (rawEntries.isNotEmpty() && rawEntries.first().first < now - rawRetentionMs) {
            rawEntries.removeFirst()
        }
        if (::rawLog.isInitialized) {
            rawLog.text = rawEntries.joinToString("\n") { it.second }
        }
    }

    private fun applyParsedLines(lines: List<String>) {
        lines.forEach { line ->
            appendRaw(line)
            reducer.applyLine(state, line)
        }
    }

    private fun verticalBody(colors: UiColors) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colors.background)
    }

    private fun scroll(child: View) = ScrollView(this).apply {
        addView(child)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
    }

    private fun sectionLabel(textValue: String, colors: UiColors) = TextView(this).apply {
        text = textValue
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colors.muted)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun styleButton(button: Button, colors: UiColors, filled: Boolean) {
        button.textSize = 14f
        button.isAllCaps = false
        button.maxLines = 2
        button.setTextColor(if (filled) colors.accentText else colors.text)
        button.background = rounded(if (filled) colors.accent else colors.surface, colors.border)
        button.minHeight = 0
        button.minWidth = 0
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun headerButtonParams() = if (isVeryCompactWidth()) {
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44),
        ).apply {
            topMargin = dp(8)
        }
    } else {
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44),
        ).apply {
            marginStart = dp(8)
        }
    }

    private fun tabButtonParams() = if (isCompactWidth()) {
        LinearLayout.LayoutParams(dp(100), dp(44)).apply {
            marginEnd = dp(6)
        }
    } else {
        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginEnd = dp(6)
        }
    }

    private fun actionButtonParams(first: Boolean) = LinearLayout.LayoutParams(
        0,
        dp(44),
        1f,
    ).apply {
        if (!first) marginStart = dp(8)
    }

    private fun commandEntryParams() = if (isCompactWidth()) {
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        )
    } else {
        LinearLayout.LayoutParams(0, dp(52), 1f)
    }

    private fun sendButtonParams() = if (isCompactWidth()) {
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply {
            topMargin = dp(8)
        }
    } else {
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(52),
        ).apply {
            marginStart = dp(8)
        }
    }

    private fun commandButtonParams() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(52)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(0, 0, dp(8), dp(8))
    }

    private fun commandColumnCount(): Int {
        val width = screenWidthDp()
        return when {
            width >= 720 -> 3
            width >= 420 -> 2
            else -> 1
        }
    }

    private fun isVeryCompactWidth() = screenWidthDp() < 360

    private fun isCompactWidth() = screenWidthDp() < 420

    private fun screenWidthDp(): Int {
        val configurationWidth = resources.configuration.screenWidthDp
        if (configurationWidth > 0) return configurationWidth
        return (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
    }

    private fun colors() = if (darkMode) {
        UiColors(
            background = Color.rgb(18, 21, 24),
            surface = Color.rgb(36, 40, 44),
            surfaceStrong = Color.rgb(24, 28, 32),
            text = Color.rgb(238, 241, 244),
            muted = Color.rgb(170, 178, 186),
            accent = Color.rgb(38, 132, 98),
            accentText = Color.WHITE,
            border = Color.rgb(62, 70, 76),
        )
    } else {
        UiColors(
            background = Color.rgb(250, 251, 252),
            surface = Color.rgb(238, 241, 242),
            surfaceStrong = Color.WHITE,
            text = Color.rgb(38, 45, 51),
            muted = Color.rgb(105, 116, 124),
            accent = Color.rgb(36, 126, 92),
            accentText = Color.WHITE,
            border = Color.rgb(214, 220, 224),
        )
    }

    private fun applySystemBars(colors: UiColors) {
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = if (darkMode) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    private fun topInset(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return window.decorView.rootWindowInsets
                ?.getInsets(WindowInsets.Type.statusBars())
                ?.top ?: statusBarFallback()
        }
        return statusBarFallback()
    }

    private fun statusBarFallback(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
