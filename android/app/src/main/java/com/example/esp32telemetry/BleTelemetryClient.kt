package com.example.esp32telemetry

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

data class BleDeviceInfo(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
)

class BleTelemetryClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onDeviceFound(device: BleDeviceInfo)
        fun onStatus(message: String)
        fun onLog(message: String)
        fun onConnected(name: String, address: String, canWrite: Boolean)
        fun onDisconnected(expected: Boolean)
        fun onData(data: ByteArray)
        fun onCommandSent(command: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val notifyCharacteristics = mutableListOf<BluetoothGattCharacteristic>()
    private var selectedDevice: BluetoothDevice? = null
    private var wantConnected = false
    private var userDisconnect = false
    private var writeWithResponse = true
    private var reconnectAttempt = 0
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private var pendingCommandForLog: String? = null

    fun scan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            listener.onError("Bluetooth LE scanner is not available.")
            return
        }
        if (!hasScanPermission()) {
            listener.onError("Bluetooth scan permission is missing.")
            return
        }

        stopScan()
        listener.onStatus("Scanning...")
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = safeDeviceName(device) ?: result.scanRecord?.deviceName ?: "Unknown"
                listener.onDeviceFound(BleDeviceInfo(name, device.address, device))
            }

            override fun onScanFailed(errorCode: Int) {
                listener.onError("BLE scan failed: $errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        mainHandler.postDelayed({ stopScan() }, 8000)
    }

    fun stopScan() {
        val callback = scanCallback ?: return
        if (hasScanPermission()) {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        }
        scanCallback = null
    }

    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            listener.onError("Bluetooth connect permission is missing.")
            return
        }
        stopScan()
        selectedDevice = device
        wantConnected = true
        userDisconnect = false
        reconnectAttempt = 0
        listener.onStatus("Connecting...")
        gatt?.close()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wantConnected = false
        userDisconnect = true
        mainHandler.removeCallbacksAndMessages(null)
        if (hasConnectPermission()) {
            gatt?.disconnect()
        }
        closeGatt(expected = true)
    }

    fun shutdown() {
        disconnect()
        stopScan()
    }

    fun send(command: String) {
        val clean = command.trim()
        if (clean.isEmpty()) return
        val characteristic = writeCharacteristic
        val activeGatt = gatt
        if (activeGatt == null || characteristic == null) {
            listener.onError("No writable BLE characteristic is connected.")
            return
        }
        if (!hasConnectPermission()) {
            listener.onError("Bluetooth connect permission is missing.")
            return
        }

        val bytes = "$clean\n".toByteArray(Charsets.UTF_8)
        if (bytes.size > Protocol.commandChunkSize) {
            listener.onLog("! command is ${bytes.size} bytes; sending in ${(bytes.size + Protocol.commandChunkSize - 1) / Protocol.commandChunkSize} BLE chunks")
        }
        bytes.asList()
            .chunked(Protocol.commandChunkSize)
            .map { it.toByteArray() }
            .forEach { pendingWrites.add(it) }
        pendingCommandForLog = clean
        drainWrites()
    }

    private fun drainWrites() {
        if (writeInProgress) return
        val activeGatt = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val chunk = pendingWrites.poll() ?: run {
            pendingCommandForLog?.let { listener.onCommandSent(it) }
            pendingCommandForLog = null
            return
        }

        writeInProgress = true
        characteristic.writeType = if (writeWithResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(
                characteristic,
                chunk,
                characteristic.writeType,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            activeGatt.writeCharacteristic(characteristic)
        }

        if (!started) {
            writeInProgress = false
            pendingWrites.clear()
            pendingCommandForLog = null
            listener.onError("BLE write could not be started.")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (hasConnectPermission()) {
                    gatt.discoverServices()
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val expected = userDisconnect || !wantConnected
                closeGatt(expected)
                if (!expected) scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Service discovery failed: $status")
                return
            }

            logCharacteristics(gatt.services)
            notifyCharacteristics.clear()
            notifyCharacteristics += notifyChoices(gatt.services)
            writeCharacteristic = writeChoice(gatt.services)

            subscribeNext(gatt, 0)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val index = notifyCharacteristics.indexOfFirst { it.uuid == descriptor.characteristic.uuid }
            subscribeNext(gatt, index + 1)
        }

        @Deprecated("Used on API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            listener.onData(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            listener.onData(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeInProgress = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("BLE write failed: $status")
                return
            }
            mainHandler.postDelayed({ drainWrites() }, 30)
        }
    }

    private fun subscribeNext(gatt: BluetoothGatt, index: Int) {
        if (index >= notifyCharacteristics.size) {
            val canWrite = writeCharacteristic != null
            listener.onLog("! active notify subscriptions: ${notifyCharacteristics.size}")
            writeCharacteristic?.let {
                val responseText = if (writeWithResponse) "with response" else "without response"
                listener.onLog("! using write ${it.uuid} handle unknown $responseText")
            }
            val device = selectedDevice
            listener.onConnected(
                safeDeviceName(device) ?: "Unknown",
                device?.address ?: "-",
                canWrite,
            )
            return
        }

        val characteristic = notifyCharacteristics[index]
        if (!hasConnectPermission()) return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(Protocol.clientCharacteristicConfig)
        if (descriptor == null) {
            listener.onLog("! subscribed to notify ${characteristic.uuid}")
            subscribeNext(gatt, index + 1)
            return
        }

        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        listener.onLog("! subscribed to notify ${characteristic.uuid}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun closeGatt(expected: Boolean) {
        pendingWrites.clear()
        pendingCommandForLog = null
        writeInProgress = false
        writeCharacteristic = null
        notifyCharacteristics.clear()
        val activeGatt = gatt
        gatt = null
        if (hasConnectPermission()) {
            activeGatt?.close()
        }
        listener.onDisconnected(expected)
    }

    private fun scheduleReconnect() {
        val device = selectedDevice ?: return
        val delays = longArrayOf(1000, 2000, 4000, 8000)
        val delay = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt += 1
        listener.onStatus("Disconnected. Reconnecting in ${delay / 1000}s...")
        mainHandler.postDelayed({
            if (wantConnected) connect(device)
        }, delay)
    }

    private fun notifyChoices(services: List<BluetoothGattService>): List<BluetoothGattCharacteristic> {
        return services
            .flatMap { it.characteristics }
            .filter {
                val notify = it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val indicate = it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                (notify || indicate) && it.uuid != Protocol.serviceChanged
            }
            .sortedWith(characteristicComparator(Protocol.appTelemetryNotify))
    }

    private fun writeChoice(services: List<BluetoothGattService>): BluetoothGattCharacteristic? {
        val selected = services
            .flatMap { it.characteristics }
            .filter {
                val write = it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                val noResponse = it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                write || noResponse
            }
            .sortedWith(characteristicComparator(Protocol.appControlWrite))
            .firstOrNull()

        selected?.let {
            writeWithResponse = it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0
        }
        return selected
    }

    private fun characteristicComparator(preferredUuid: UUID): Comparator<BluetoothGattCharacteristic> {
        return compareBy(
            { characteristicRank(it.uuid, preferredUuid) },
            { it.uuid.toString() },
        )
    }

    private fun characteristicRank(uuid: UUID, preferredUuid: UUID): Int {
        return when {
            uuid == preferredUuid -> 0
            uuid in Protocol.commonSerialUuids -> 1
            !isBluetoothSigUuid(uuid) -> 2
            else -> 3
        }
    }

    private fun isBluetoothSigUuid(uuid: UUID): Boolean {
        return uuid.toString().endsWith("-0000-1000-8000-00805f9b34fb")
    }

    private fun logCharacteristics(services: List<BluetoothGattService>) {
        listener.onLog("! discovered writable/notify characteristics:")
        services.flatMap { it.characteristics }.forEach { characteristic ->
            val props = mutableListOf<String>()
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props += "notify"
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props += "indicate"
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props += "write"
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props += "write-without-response"
            if (props.isNotEmpty()) {
                val type = if (isBluetoothSigUuid(characteristic.uuid)) "standard" else "app"
                listener.onLog("!   ${characteristic.uuid} props=${props.joinToString(",")} $type")
            }
        }
    }

    private fun safeDeviceName(device: BluetoothDevice?): String? {
        if (device == null || !hasConnectPermission()) return null
        return device.name
    }

    private fun hasScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
