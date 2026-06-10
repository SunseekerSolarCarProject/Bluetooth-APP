package com.example.esp32telemetry

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity(), BleTelemetryClient.Listener {
    private lateinit var bleClient: BleTelemetryClient
    private val parser = TelemetryParser()
    private val reducer = TelemetryReducer()
    private val state = TelemetryState()
    private val devices = linkedMapOf<String, BleDeviceInfo>()
    private val fieldViews = mutableMapOf<String, TextView>()
    private val rawEntries = ArrayDeque<Pair<Long, String>>()
    private val rawRetentionMs = 5 * 60 * 1000L
    private val timestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val rtcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd,HH:mm:ss")
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            refreshFields()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private lateinit var statusView: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var commandEntry: EditText
    private lateinit var rawLog: TextView
    private lateinit var disconnectButton: Button
    private lateinit var commandButtons: List<Button>
    private lateinit var writeDependentViews: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        statusView = TextView(this).apply {
            text = "Disconnected"
            textSize = 18f
        }
        root.addView(statusView)

        val topControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scanButton = Button(this).apply {
            text = "Scan"
            setOnClickListener {
                devices.clear()
                deviceList.removeAllViews()
                bleClient.scan()
            }
        }
        disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { bleClient.disconnect() }
        }
        topControls.addView(scanButton)
        topControls.addView(disconnectButton)
        root.addView(topControls)

        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(deviceList)

        val dashboard = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = true
        }
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
            "Speed" to "speed",
            "Temperature / pressure / humidity" to "environment",
            "SD card status" to "sd_card_status",
            "Telemetry source / age" to "telemetry_source",
            "ESP32 sequence / status" to "esp32_status",
            "Last response" to "last_response",
        )
        fields.forEach { (label, key) ->
            dashboard.addView(TextView(this).apply {
                text = label
                setPadding(0, 4, 18, 4)
            })
            val value = TextView(this).apply {
                text = "-"
                setPadding(0, 4, 0, 4)
            }
            fieldViews[key] = value
            dashboard.addView(value)
        }
        root.addView(dashboard)

        val commandPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val commandGrid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = true
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
        commandButtons.forEach { commandGrid.addView(it) }
        commandPanel.addView(commandGrid)

        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        commandEntry = EditText(this).apply {
            hint = "Custom command"
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        customRow.addView(commandEntry)
        customRow.addView(sendButton)
        commandPanel.addView(customRow)
        root.addView(commandPanel)

        rawLog = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(ScrollView(this).apply {
            addView(rawLog)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        })

        writeDependentViews = commandButtons + listOf(commandEntry, sendButton)
        setConnectedControls(connected = false, canWrite = false)
        setContentView(root)
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
        deviceList.addView(Button(this).apply {
            text = "${device.name} [${device.address}]"
            setOnClickListener { bleClient.connect(device.device) }
        })
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
        statusView.text = "Connected to ${state.deviceName}"
        appendRaw("! connected to ${state.deviceName}")
        if (!canWrite) appendRaw("! no writable characteristic found; receive-only mode")
        setConnectedControls(connected = true, canWrite = canWrite)
        refreshFields()
    }

    override fun onDisconnected(expected: Boolean) = runOnUiThread {
        state.connected = false
        statusView.text = if (expected) "Disconnected" else "Disconnected - reconnecting"
        appendRaw(if (expected) "! disconnected" else "! disconnected unexpectedly")
        setConnectedControls(connected = false, canWrite = false)
        refreshFields()
    }

    override fun onData(data: ByteArray) = runOnUiThread {
        parser.feed(data).forEach { line ->
            appendRaw(line)
            reducer.applyLine(state, line)
        }
        refreshFields()
    }

    override fun onCommandSent(command: String) = runOnUiThread {
        appendRaw("> $command (BLE write complete)")
    }

    override fun onError(message: String) = runOnUiThread {
        appendRaw("! $message")
    }

    private fun setConnectedControls(connected: Boolean, canWrite: Boolean) {
        disconnectButton.isEnabled = connected
        writeDependentViews.forEach { it.isEnabled = connected && canWrite }
    }

    private fun refreshFields() {
        val connection = if (state.connected) "Connected - ${state.deviceName}" else "Disconnected"
        val environment = "${state.temperature} / ${state.pressure} / ${state.humidity}"
        val values = mapOf(
            "connection" to connection,
            "last_update_time" to state.lastUpdateTime,
            "seconds_since_last_update" to state.secondsSinceLastUpdate(),
            "can_rx_count" to state.canRxCount.toString(),
            "last_can_id" to state.lastCanId,
            "last_can_data" to state.lastCanData,
            "gps_status" to state.gpsStatus,
            "gps_speed" to state.gpsSpeed,
            "latest_gps_sentence" to state.latestGpsSentence,
            "speed" to state.speed,
            "environment" to environment,
            "sd_card_status" to state.sdCardStatus,
            "telemetry_source" to state.telemetrySource,
            "esp32_status" to state.esp32Status,
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
        rawLog.text = rawEntries.joinToString("\n") { it.second }
    }
}
