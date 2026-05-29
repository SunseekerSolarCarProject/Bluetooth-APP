from dataclasses import dataclass


NORDIC_UART_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
NORDIC_UART_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
SERVICE_CHANGED = "00002a05-0000-1000-8000-00805f9b34fb"
BLUETOOTH_SIG_SUFFIX = "-0000-1000-8000-00805f9b34fb"
BLE_COMMAND_CHUNK_SIZE = 20

COMMON_SERIAL_UUIDS = {
    NORDIC_UART_RX,
    NORDIC_UART_TX,
    "0000ffe1-0000-1000-8000-00805f9b34fb",
}

@dataclass(frozen=True)
class CommandSpec:
    label: str
    payload: str
    description: str


COMMANDS = [
    CommandSpec("GET_STATUS", "GET_STATUS", "Request current ESP32/STM32 status."),
    CommandSpec("SET_RATE,1000", "SET_RATE,1000", "Set telemetry rate to 1000 ms."),
    CommandSpec("SET_RATE,500", "SET_RATE,500", "Set telemetry rate to 500 ms."),
    CommandSpec("SET_MODE,RAW", "SET_MODE,RAW", "Send full $LOG rows."),
    CommandSpec("SET_MODE,COMPACT", "SET_MODE,COMPACT", "Send compact TEL rows."),
    CommandSpec(
        "FIELDS,STCG",
        "FIELDS,STCG",
        "Compact field set: speed, temp, CAN, GPS.",
    ),
    CommandSpec("PING", "PING", "Check command path and ESP32 responsiveness."),
    CommandSpec("START_LOG", "START_LOG", "Start firmware/SD logging."),
    CommandSpec("STOP_LOG", "STOP_LOG", "Stop firmware/SD logging."),
    CommandSpec("CALIBRATE_IMU", "CALIBRATE_IMU", "Request IMU calibration."),
    CommandSpec("CLEAR_ERRORS", "CLEAR_ERRORS", "Clear firmware error flags."),
]
