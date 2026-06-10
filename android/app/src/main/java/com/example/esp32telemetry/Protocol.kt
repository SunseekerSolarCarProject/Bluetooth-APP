package com.example.esp32telemetry

import java.util.UUID

object Protocol {
    val serviceChanged: UUID = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
    val nordicUartRx: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val nordicUartTx: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val appTelemetryNotify: UUID = UUID.fromString("02000010-236d-4f93-9145-7b5a2032548f")
    val appControlWrite: UUID = UUID.fromString("03000010-236d-4f93-9145-7b5a2032548f")
    val clientCharacteristicConfig: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val commandChunkSize = 20

    val commonSerialUuids = setOf(
        nordicUartRx,
        nordicUartTx,
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
    )
}

data class CommandSpec(
    val label: String,
    val payload: String,
    val description: String,
)

val defaultCommands = listOf(
    CommandSpec("GET_STATUS", "GET_STATUS", "Request current ESP32 telemetry status."),
    CommandSpec("SET_RATE,1000", "SET_RATE,1000", "Set telemetry rate to 1000 ms."),
    CommandSpec("SET_RATE,500", "SET_RATE,500", "Set telemetry rate to 500 ms."),
    CommandSpec("SET_MODE,RAW", "SET_MODE,RAW", "Send full \$LOG rows."),
    CommandSpec("SET_MODE,COMPACT", "SET_MODE,COMPACT", "Send compact TEL rows."),
    CommandSpec("FIELDS,STCG", "FIELDS,STCG", "Compact field set: speed, temp, CAN, GPS."),
    CommandSpec("PING", "PING", "Check command path and ESP32 responsiveness."),
    CommandSpec("START_LOG", "START_LOG", "Start firmware/SD logging."),
    CommandSpec("STOP_LOG", "STOP_LOG", "Stop firmware/SD logging."),
    CommandSpec("CALIBRATE_IMU", "CALIBRATE_IMU", "Request IMU calibration."),
    CommandSpec("CLEAR_ERRORS", "CLEAR_ERRORS", "Clear firmware error flags."),
)
