package com.example.esp32telemetry

import android.os.SystemClock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class TelemetryState(
    var connected: Boolean = false,
    var deviceName: String = "-",
    var lastUpdateTime: String = "-",
    var lastUpdateElapsed: Long? = null,
    var canRxCount: Int = 0,
    var lastCanId: String = "-",
    var lastCanData: String = "-",
    var gpsStatus: String = "-",
    var gpsSpeed: String = "-",
    var latestGpsSentence: String = "-",
    var imuSpeed: String = "-",
    var temperature: String = "-",
    var pressure: String = "-",
    var humidity: String = "-",
    var sdCardStatus: String = "-",
    var stm32Alive: String = "-",
    var esp32Alive: String = "-",
    var lastResponse: String = "-",
) {
    fun secondsSinceLastUpdate(now: Long = SystemClock.elapsedRealtime()): String {
        val last = lastUpdateElapsed ?: return "-"
        return "%.1fs".format(((now - last) / 1000.0).coerceAtLeast(0.0))
    }
}

class TelemetryParser {
    private val prefixes = listOf("\$LOG,", "TEL,", "STATUS,")
    private var buffer = ""
    private var lastBufferUpdate = 0L
    private val stalePartialMs = 1500L

    fun feed(data: ByteArray): List<String> {
        val now = SystemClock.elapsedRealtime()
        buffer += data.toString(Charsets.UTF_8)
        lastBufferUpdate = now
        val lines = mutableListOf<String>()

        while (buffer.contains('\n') || buffer.contains('\r')) {
            val newline = listOf(buffer.indexOf('\n'), buffer.indexOf('\r'))
                .filter { it >= 0 }
                .minOrNull() ?: break
            val line = buffer.substring(0, newline).trim()
            buffer = buffer.substring(newline + 1).trimStart('\r', '\n')
            if (line.isNotBlank()) {
                lines += splitCompleteRecords(line)
            }
        }

        val extracted = extractRecords(buffer)
        lines += extracted.first
        buffer = extracted.second
        if (lines.isNotEmpty() && buffer.isNotEmpty()) {
            lastBufferUpdate = now
        }

        if (buffer.length > 4096 && recordStarts(buffer).isEmpty()) {
            lines += buffer.trim()
            buffer = ""
        }

        return lines
    }

    fun flushStalePartial(): List<String> {
        if (buffer.isBlank()) return emptyList()
        val elapsed = SystemClock.elapsedRealtime() - lastBufferUpdate
        if (elapsed < stalePartialMs) return emptyList()

        val record = buffer.trim()
        buffer = ""
        return listOf(record)
    }

    private fun splitCompleteRecords(text: String): List<String> {
        val (records, remainder) = extractRecords(text, forceLast = true)
        return if (remainder.isNotBlank()) records + remainder.trim() else records
    }

    private fun extractRecords(textValue: String, forceLast: Boolean = false): Pair<List<String>, String> {
        val text = textValue.trim()
        if (text.isEmpty()) return emptyList<String>() to ""

        val starts = recordStarts(text)
        if (starts.isEmpty()) return emptyList<String>() to text

        val records = mutableListOf<String>()
        if (starts.first() > 0) {
            val leading = text.substring(0, starts.first()).trim()
            if (leading.isNotEmpty()) records += leading
        }

        starts.forEachIndexed { index, start ->
            val isLast = index == starts.lastIndex
            val end = if (isLast) text.length else starts[index + 1]
            val record = text.substring(start, end).trim()
            if (record.isEmpty()) return@forEachIndexed
            if (!isLast || forceLast) {
                records += record
            } else {
                return records to record
            }
        }

        return records to ""
    }

    private fun recordStarts(text: String): List<Int> {
        val starts = mutableListOf<Int>()
        var inQuotes = false
        for (index in text.indices) {
            val char = text[index]
            if (char == '"') {
                inQuotes = !inQuotes
            } else if (!inQuotes && prefixes.any { text.startsWith(it, index) }) {
                starts += index
            }
        }
        return starts
    }

}

class TelemetryReducer {
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun applyLine(state: TelemetryState, line: String): Boolean {
        state.lastResponse = line

        val parts = line.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        val messageType = parts.first().uppercase()
        val pairs = parsePairs(parts.drop(1))
        var updatedTelemetry = true

        when (messageType) {
            "PONG" -> {
                state.esp32Alive = pairs["esp32_ms"] ?: pairs["ms"] ?: "alive"
                updatedTelemetry = false
            }
            "ACK" -> {
                state.lastResponse = line
                updatedTelemetry = false
            }
            "TEL" -> {
                applyTelPairs(state, pairs)
                updatedTelemetry = pairs.isNotEmpty()
            }
            "STATUS" -> {
                applyStatusPairs(state, pairs)
                updatedTelemetry = pairs.isNotEmpty()
            }
            "\$LOG" -> updatedTelemetry = applyLogLine(state, line)
            "CAN" -> {
                state.canRxCount += 1
                state.lastCanId = pairs["id"] ?: pairs["can_id"] ?: state.lastCanId
                state.lastCanData = pairs["data"] ?: state.lastCanData
            }
            "GPS" -> {
                state.gpsStatus = pairs["status"] ?: pairs["gps"] ?: state.gpsStatus
                state.gpsSpeed = pairs["gps_speed"] ?: pairs["gps_speed_kph"] ?: pairs["gps_kph"] ?: pairs["speed"] ?: state.gpsSpeed
                state.latestGpsSentence = pairs["sentence"] ?: pairs["nmea"] ?: state.latestGpsSentence
            }
            "IMU" -> {
                state.imuSpeed = pairs["speed"] ?: pairs["imu_speed"] ?: state.imuSpeed
            }
            "ENV", "BME", "TEMP" -> applyEnvironmentPairs(state, pairs)
            "SD" -> {
                state.sdCardStatus = pairs["status"] ?: pairs["sd"] ?: line
            }
            "STM32" -> {
                state.stm32Alive = pairs["alive"] ?: pairs["ms"] ?: "alive"
            }
            "ESP32" -> {
                state.esp32Alive = pairs["alive"] ?: pairs["ms"] ?: "alive"
            }
            else -> {
                if (pairs.isEmpty()) {
                    updatedTelemetry = false
                } else {
                    applyStatusPairs(state, pairs)
                }
            }
        }

        if (updatedTelemetry) {
            state.lastUpdateTime = LocalDateTime.now().format(timeFormatter)
            state.lastUpdateElapsed = SystemClock.elapsedRealtime()
        }
        return updatedTelemetry
    }

    private fun applyTelPairs(state: TelemetryState, pairs: Map<String, String>) {
        pairs["seq"]?.let { state.esp32Alive = "seq $it" }
        pairs["age_ms"]?.let { state.stm32Alive = "age $it ms" }

        if (applyShiftedCompactFields(state, pairs)) {
            applyTelStatusPairs(state, pairs)
            return
        }

        pairs["speed"]?.let { state.imuSpeed = it }
        pairs["temp"]?.let { state.temperature = it }
        pairs["temperature"]?.let { state.temperature = it }
        pairs["pressure"]?.let { state.pressure = it }
        pairs["humidity"]?.let { state.humidity = it }
        applyTelStatusPairs(state, pairs)
    }

    private fun applyShiftedCompactFields(state: TelemetryState, pairs: Map<String, String>): Boolean {
        val speed = pairs["speed"]?.toDoubleOrNull() ?: return false
        val compactTemp = (pairs["temp"] ?: pairs["temperature"])?.toDoubleOrNull() ?: return false
        if (speed !in 30000.0..120000.0 || compactTemp !in 0.0..100.0) return false

        state.pressure = pairs["speed"].orEmpty()
        state.humidity = pairs["temp"] ?: pairs["temperature"] ?: state.humidity
        if (state.temperature == "-" || state.temperature == state.humidity) {
            state.temperature = "-"
        }
        if (state.imuSpeed == pairs["speed"]) {
            state.imuSpeed = "-"
        }
        return true
    }

    private fun applyTelStatusPairs(state: TelemetryState, pairs: Map<String, String>) {
        pairs["can"]?.let {
            state.lastCanData = "CAN $it"
            state.canRxCount = it.toIntOrNull() ?: state.canRxCount
        }
        pairs["can_id"]?.let { state.lastCanId = it }
        pairs["gps"]?.let { state.gpsStatus = it }
        pairs["gps_status"]?.let { state.gpsStatus = it }
        state.gpsSpeed = pairs["gps_speed"] ?: pairs["gps_speed_kph"] ?: pairs["gps_kph"] ?: state.gpsSpeed
        pairs["gps_sentence"]?.let { state.latestGpsSentence = it }
        pairs["raw_len"]?.let { state.lastCanData = "${state.lastCanData} raw_len=$it" }
    }

    private fun applyLogLine(state: TelemetryState, line: String): Boolean {
        val fields = parseCsv(line)
        return when {
            isFullCurrentLog(fields) -> {
                applyFullCurrentLogFields(state, fields)
                true
            }
            isExtendedCurrentLog(fields) -> {
                applyExtendedCurrentLogFields(state, fields)
                true
            }
            fields.size >= 23 -> {
                applyLegacyLogFields(state, fields)
                true
            }
            fields.size >= 21 -> {
                applyCurrentLogFields(state, fields)
                true
            }
            else -> false
        }
    }

    private fun applyCurrentLogFields(state: TelemetryState, fields: List<String>) {
        state.esp32Alive = "seq ${fields[1]} uptime ${fields[2]} ms"
        state.stm32Alive = if (fields[4] == "1") "OK" else fields[4]
        state.imuSpeed = fields[11]
        state.temperature = fields[12]
        state.pressure = fields[13]
        state.humidity = fields[14]
        state.sdCardStatus = formatSdStatus(fields[15])
        state.lastCanId = fields[16]
        state.lastCanData = "${fields[17]},${fields[18]}"
        state.latestGpsSentence = fields[20]
        state.gpsStatus = gpsStatusFromSentence(fields[20])
    }

    private fun applyFullCurrentLogFields(state: TelemetryState, fields: List<String>) {
        state.esp32Alive = "seq ${fields[1]} uptime ${fields[2]} ms"
        state.stm32Alive = if (fields[4] == "1") "OK" else fields[4]
        state.imuSpeed = fields[11]
        state.gpsSpeed = fields[12]
        state.temperature = fields[23]
        state.pressure = fields[24]
        state.humidity = fields[25]
        state.sdCardStatus = formatSdStatus(fields[26])
        state.lastCanId = fields[27]
        state.lastCanData = "${fields[28]},${fields[29]}"
        state.latestGpsSentence = fields[30]
        val sentenceStatus = gpsStatusFromSentence(fields[30])
        val blockStatus = fields[16]
        state.gpsStatus = if (sentenceStatus != "-") sentenceStatus else if (blockStatus.isBlank() || blockStatus == "NONE") "-" else blockStatus
    }

    private fun applyExtendedCurrentLogFields(state: TelemetryState, fields: List<String>) {
        state.esp32Alive = "seq ${fields[1]} uptime ${fields[2]} ms"
        state.stm32Alive = if (fields[4] == "1") "OK" else fields[4]
        state.imuSpeed = fields[11]
        state.gpsSpeed = fields[12]
        state.temperature = fields[16]
        state.pressure = fields[17]
        state.humidity = fields[18]
        state.sdCardStatus = formatSdStatus(fields[19])
        state.lastCanId = fields[20]
        state.lastCanData = "${fields[21]},${fields[22]}" + if (fields[23].isNotBlank()) ",${fields[23]}" else ""
        state.latestGpsSentence = fields[24]
        val sentenceStatus = gpsStatusFromSentence(fields[24])
        val blockStatus = fields[15]
        state.gpsStatus = if (sentenceStatus != "-") sentenceStatus else if (blockStatus.isBlank() || blockStatus == "NONE") "-" else blockStatus
    }

    private fun applyLegacyLogFields(state: TelemetryState, fields: List<String>) {
        state.esp32Alive = "uptime ${fields[2]} ms"
        state.stm32Alive = if (fields[4] == "1") "OK" else fields[4]
        if (fields[6] == "1") state.esp32Alive = "OK"
        state.canRxCount = fields[8].toIntOrNull() ?: state.canRxCount
        state.imuSpeed = fields[13]
        state.temperature = fields[14]
        state.pressure = fields[15]
        state.humidity = fields[16]
        state.sdCardStatus = formatSdStatus(fields[17])
        state.lastCanId = fields[18]
        state.lastCanData = "${fields[19]},${fields[20]}"
        state.latestGpsSentence = fields[22]
        state.gpsStatus = gpsStatusFromSentence(fields[22])
    }

    private fun isFullCurrentLog(fields: List<String>): Boolean {
        return fields.size >= 31
            && fields[11].toDoubleOrNull() != null
            && fields[12].toDoubleOrNull() != null
            && fields[23].toDoubleOrNull() != null
            && fields[24].toDoubleOrNull() != null
            && fields[25].toDoubleOrNull() != null
    }

    private fun isExtendedCurrentLog(fields: List<String>): Boolean {
        return fields.size >= 25
            && fields[5].toDoubleOrNull() != null
            && fields[16].toDoubleOrNull() != null
            && fields[17].toDoubleOrNull() != null
            && fields[18].toDoubleOrNull() != null
    }

    private fun applyStatusPairs(state: TelemetryState, pairs: Map<String, String>) {
        val sourceParts = mutableListOf<String>()
        pairs["stm32"]?.let { sourceParts += "src=$it" }
        pairs["spi_task"]?.let { sourceParts += "spi=$it" }
        pairs["age_ms"]?.let { sourceParts += "age=$it ms" }
        if (sourceParts.isNotEmpty()) state.stm32Alive = sourceParts.joinToString(" ")

        val esp32Parts = mutableListOf<String>()
        pairs["esp32"]?.let { esp32Parts += "esp32=$it" }
        pairs["ble"]?.let { esp32Parts += "ble=$it" }
        pairs["seq"]?.let { esp32Parts += "seq=$it" }
        pairs["mode"]?.let { esp32Parts += "mode=$it" }
        pairs["rate_ms"]?.let { esp32Parts += "rate=$it ms" }
        if (esp32Parts.isNotEmpty()) state.esp32Alive = esp32Parts.joinToString(" ")

        pairs["can"]?.let { state.lastCanData = "CAN $it" }
        pairs["sd"]?.let { state.sdCardStatus = it }
        pairs["gps"]?.let { state.gpsStatus = it }
        state.gpsSpeed = pairs["gps_speed"] ?: pairs["gps_speed_kph"] ?: pairs["gps_kph"] ?: state.gpsSpeed
        applyEnvironmentPairs(state, pairs)
    }

    private fun applyEnvironmentPairs(state: TelemetryState, pairs: Map<String, String>) {
        pairs["temp"]?.let { state.temperature = it }
        pairs["temperature"]?.let { state.temperature = it }
        pairs["pressure"]?.let { state.pressure = it }
        pairs["humidity"]?.let { state.humidity = it }
    }

    private fun formatSdStatus(value: String) = if (value == "0") "OK" else value
}

fun parsePairs(parts: List<String>): Map<String, String> {
    return parts.mapNotNull {
        val splitAt = it.indexOf("=")
        if (splitAt < 0) null else it.substring(0, splitAt).trim().lowercase() to it.substring(splitAt + 1).trim()
    }.toMap()
}

fun gpsStatusFromSentence(sentence: String): String {
    if (sentence.isBlank() || sentence == "-" || sentence == "NO_GPS" || sentence == "NO_DATA") return "-"
    if (sentence.startsWith("\$GNGGA") || sentence.startsWith("\$GPGGA")) {
        val parts = sentence.split(",")
        if (parts.size > 6) return if (parts[6].isNotBlank() && parts[6] != "0") "FIX" else "NO_FIX"
    }
    if (sentence.startsWith("\$GNGSA") || sentence.startsWith("\$GPGSA")) {
        val parts = sentence.split(",")
        if (parts.size > 2) return if (parts[2].isNotBlank() && parts[2] != "1") "FIX" else "NO_FIX"
    }
    return "seen"
}

fun parseCsv(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var index = 0

    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                field.append('"')
                index += 1
            }
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                fields += field.toString()
                field.clear()
            }
            else -> field.append(char)
        }
        index += 1
    }
    fields += field.toString()
    return fields
}
