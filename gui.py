import contextlib
import csv
import queue
import time
from collections import deque
from datetime import datetime
from typing import Any

from bleak.backends.device import BLEDevice
from PyQt6.QtCore import QDateTime, QTimer, Qt
from PyQt6.QtWidgets import (
    QComboBox,
    QDateTimeEdit,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QSizePolicy,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from app_config import COMMANDS
from ble_worker import BleWorker
from telemetry import (
    TelemetryParser,
    TelemetryState,
    gps_status_from_sentence,
    parse_pairs,
)


RAW_TERMINAL_RETENTION_SECONDS = 5 * 60


class DashboardWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("ESP32 BLE Telemetry")
        self.resize(1180, 760)
        self.setMinimumSize(980, 640)

        self.events: queue.Queue[tuple[str, Any]] = queue.Queue()
        self.worker = BleWorker(self.events)
        self.worker.start()
        self.parser = TelemetryParser()
        self.state = TelemetryState()
        self.devices: list[BLEDevice] = []
        self.field_labels: dict[str, QLabel] = {}
        self.can_write = False
        self.last_disconnect_message = ""
        self.pending_command: tuple[str, float] | None = None
        self.raw_entries: deque[tuple[float, str]] = deque()

        self._build_ui()
        self._set_connected_controls(False)

        self.event_timer = QTimer(self)
        self.event_timer.timeout.connect(self._process_events)
        self.event_timer.start(100)

        self.elapsed_timer = QTimer(self)
        self.elapsed_timer.timeout.connect(self._refresh_elapsed_time)
        self.elapsed_timer.start(250)

        self.command_timer = QTimer(self)
        self.command_timer.timeout.connect(self._check_pending_command)
        self.command_timer.start(500)

    def _build_ui(self) -> None:
        self.setStyleSheet(
            """
            QMainWindow, QWidget {
                background: #111315;
                color: #f4f7fb;
                font-family: Segoe UI;
                font-size: 10pt;
            }
            QLabel#title {
                font-size: 18pt;
                font-weight: 600;
            }
            QLabel#status {
                color: #9aa4b2;
            }
            QWidget[panel="true"] {
                background: #191c1f;
                border: 1px solid #2c3238;
            }
            QLabel[fieldLabel="true"] {
                background: #191c1f;
                color: #f4f7fb;
            }
            QLabel[fieldValue="true"] {
                background: #191c1f;
                color: #f4f7fb;
                font-family: Cascadia Mono, Consolas, monospace;
            }
            QPushButton {
                background: #20242a;
                color: #f4f7fb;
                border: 1px solid #8d949c;
                padding: 8px 10px;
            }
            QPushButton:hover {
                background: #2b3138;
            }
            QPushButton:disabled {
                color: #68707a;
                border-color: #3a4048;
                background: #171a1e;
            }
            QPushButton#accent {
                background: #3fb984;
                color: #07130d;
                border-color: #3fb984;
                font-weight: 600;
            }
            QComboBox, QLineEdit, QTextEdit {
                background: #0e1012;
                color: #f4f7fb;
                border: 1px solid #2c3238;
                padding: 7px;
                selection-background-color: #3fb984;
                selection-color: #07130d;
            }
            QTextEdit {
                font-family: Cascadia Mono, Consolas, monospace;
            }
            """
        )

        central = QWidget()
        self.setCentralWidget(central)

        root = QVBoxLayout(central)
        root.setContentsMargins(18, 18, 18, 18)
        root.setSpacing(14)

        header = QHBoxLayout()
        title = QLabel("ESP32 BLE Telemetry")
        title.setObjectName("title")
        self.status_label = QLabel("Disconnected")
        self.status_label.setObjectName("status")
        self.status_label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        header.addWidget(title, 1)
        header.addWidget(self.status_label, 1)
        root.addLayout(header)

        controls = self._panel()
        controls_layout = QHBoxLayout(controls)
        controls_layout.setContentsMargins(12, 12, 12, 12)
        controls_layout.setSpacing(10)

        self.scan_button = QPushButton("Scan")
        self.scan_button.clicked.connect(self._scan)
        self.device_combo = QComboBox()
        self.device_combo.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.connect_button = QPushButton("Connect")
        self.connect_button.setObjectName("accent")
        self.connect_button.clicked.connect(self._connect)
        self.disconnect_button = QPushButton("Disconnect")
        self.disconnect_button.clicked.connect(self._disconnect)

        controls_layout.addWidget(self.scan_button)
        controls_layout.addWidget(self.device_combo, 1)
        controls_layout.addWidget(self.connect_button)
        controls_layout.addWidget(self.disconnect_button)
        root.addWidget(controls)

        body = QHBoxLayout()
        body.setSpacing(14)
        root.addLayout(body, 1)

        dashboard = self._panel()
        dashboard_layout = QGridLayout(dashboard)
        dashboard_layout.setContentsMargins(14, 14, 14, 14)
        dashboard_layout.setHorizontalSpacing(20)
        dashboard_layout.setVerticalSpacing(8)
        body.addWidget(dashboard, 3)

        fields = [
            ("Connection", "connection"),
            ("Last update time", "last_update_time"),
            ("Seconds since last update", "seconds_since_last_update"),
            ("CAN RX count", "can_rx_count"),
            ("Last CAN ID", "last_can_id"),
            ("Last CAN data", "last_can_data"),
            ("GPS status", "gps_status"),
            ("GPS speed", "gps_speed"),
            ("Latest GPS sentence", "latest_gps_sentence"),
            ("Speed", "imu_speed"),
            ("Temperature / pressure / humidity", "environment"),
            ("SD card status", "sd_card_status"),
            ("Telemetry source / age", "stm32_alive"),
            ("ESP32 sequence / status", "esp32_alive"),
            ("Last response", "last_response"),
        ]

        for row, (label_text, key) in enumerate(fields):
            label = QLabel(label_text)
            label.setProperty("fieldLabel", True)
            label.setAlignment(Qt.AlignmentFlag.AlignTop | Qt.AlignmentFlag.AlignLeft)
            value = QLabel("-")
            value.setProperty("fieldValue", True)
            value.setWordWrap(True)
            value.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            dashboard_layout.addWidget(label, row, 0)
            dashboard_layout.addWidget(value, row, 1)
            self.field_labels[key] = value

        dashboard_layout.setColumnStretch(0, 0)
        dashboard_layout.setColumnStretch(1, 1)

        side = QVBoxLayout()
        side.setSpacing(14)
        body.addLayout(side, 2)

        command_panel = self._panel()
        command_layout = QVBoxLayout(command_panel)
        command_layout.setContentsMargins(14, 14, 14, 14)
        command_layout.setSpacing(10)
        command_layout.addWidget(QLabel("Commands"))

        command_grid = QGridLayout()
        command_grid.setSpacing(10)
        command_layout.addLayout(command_grid)

        self.command_buttons: list[QPushButton] = []
        for index, command in enumerate(COMMANDS):
            button = QPushButton(command.label)
            button.setToolTip(f"Sends: {command.payload}\n{command.description}")
            button.clicked.connect(
                lambda checked=False, selected=command.payload: self._send_command(selected)
            )
            command_grid.addWidget(button, index // 2, index % 2)
            self.command_buttons.append(button)

        rtc_index = len(COMMANDS)
        rtc_button = QPushButton("SET_RTC,now")
        rtc_button.setToolTip("Sends the current local desktop time to the device RTC.")
        rtc_button.clicked.connect(self._send_current_rtc)
        command_grid.addWidget(rtc_button, rtc_index // 2, rtc_index % 2)
        self.command_buttons.append(rtc_button)

        rtc_layout = QHBoxLayout()
        self.rtc_datetime = QDateTimeEdit()
        self.rtc_datetime.setDisplayFormat("yyyy-MM-dd HH:mm:ss")
        self.rtc_datetime.setCalendarPopup(True)
        self.rtc_datetime.setDateTime(QDateTime.currentDateTime())
        self.rtc_datetime.setToolTip("Select a custom local date/time to send to the device RTC.")
        self.rtc_button = QPushButton("SET_RTC,selected")
        self.rtc_button.setToolTip("Sends the selected local date/time to the device RTC.")
        self.rtc_button.clicked.connect(self._send_selected_rtc)
        rtc_layout.addWidget(self.rtc_datetime, 1)
        rtc_layout.addWidget(self.rtc_button)
        command_layout.addLayout(rtc_layout)
        self.command_buttons.append(self.rtc_button)

        custom_layout = QHBoxLayout()
        self.command_entry = QLineEdit()
        self.command_entry.returnPressed.connect(self._send_custom_command)
        self.send_button = QPushButton("Send")
        self.send_button.clicked.connect(self._send_custom_command)
        custom_layout.addWidget(self.command_entry, 1)
        custom_layout.addWidget(self.send_button)
        command_layout.addLayout(custom_layout)
        side.addWidget(command_panel)

        terminal = self._panel()
        terminal_layout = QVBoxLayout(terminal)
        terminal_layout.setContentsMargins(14, 14, 14, 14)
        terminal_layout.setSpacing(10)
        terminal_layout.addWidget(QLabel("Raw terminal"))
        self.raw_text = QTextEdit()
        self.raw_text.setReadOnly(True)
        terminal_layout.addWidget(self.raw_text, 1)
        side.addWidget(terminal, 1)

    @staticmethod
    def _panel() -> QWidget:
        panel = QWidget()
        panel.setProperty("panel", True)
        return panel

    def _scan(self) -> None:
        self.scan_button.setEnabled(False)
        self.status_label.setText("Scanning...")
        self.worker.scan()

    def _connect(self) -> None:
        index = self.device_combo.currentIndex()
        if index < 0 or index >= len(self.devices):
            QMessageBox.warning(self, "No device selected", "Scan and select an ESP32 BLE device.")
            return
        self.connect_button.setEnabled(False)
        self.status_label.setText("Connecting...")
        self.worker.connect(self.devices[index])

    def _disconnect(self) -> None:
        self.worker.disconnect()

    def _send_custom_command(self) -> None:
        command = self.command_entry.text().strip()
        if command:
            self._send_command(command)
            self.command_entry.clear()

    def _send_current_rtc(self) -> None:
        now = datetime.now().strftime("%Y-%m-%d,%H:%M:%S")
        self._send_command(f"SET_RTC,{now}")

    def _send_selected_rtc(self) -> None:
        selected = self.rtc_datetime.dateTime().toPyDateTime()
        timestamp = selected.strftime("%Y-%m-%d,%H:%M:%S")
        self._send_command(f"SET_RTC,{timestamp}")

    def _send_command(self, command: str) -> None:
        self.worker.send(command)

    def _process_events(self) -> None:
        while True:
            try:
                event, payload = self.events.get_nowait()
            except queue.Empty:
                break

            if event == "status":
                self.status_label.setText(str(payload))
            elif event == "devices":
                self._set_devices(payload)
            elif event == "connected":
                self._handle_connected(payload)
            elif event == "disconnected":
                self._handle_disconnected(payload)
            elif event == "data":
                self._handle_data(payload)
            elif event == "sent":
                self._append_raw(f"> {payload} (BLE write complete)")
                self.pending_command = (str(payload), time.monotonic())
                self.state.last_response = f"write complete {payload}"
                self._refresh_fields()
            elif event == "log":
                self._append_raw(str(payload))
            elif event == "error":
                self.status_label.setText(f"Error: {payload}")
                self._append_raw(f"! {payload}")
                self._set_connected_controls(self.state.connected)

    def _set_devices(self, devices: list[BLEDevice]) -> None:
        self.devices = devices
        self.device_combo.clear()
        for device in devices:
            self.device_combo.addItem(f"{device.name or 'Unknown'} [{device.address}]")
        if devices:
            self.device_combo.setCurrentIndex(0)
        self.scan_button.setEnabled(True)

    def _handle_connected(self, payload: dict[str, Any]) -> None:
        self.state.connected = True
        self.can_write = bool(payload["can_write"])
        self.last_disconnect_message = ""
        self.state.device_name = f"{payload['name']} [{payload['address']}]"
        self.status_label.setText(f"Connected to {self.state.device_name}")
        self._append_raw(f"! connected to {self.state.device_name}")
        if not payload["can_write"]:
            self._append_raw("! no writable characteristic found; receive-only mode")
        self._set_connected_controls(True)
        self._refresh_fields()

    def _handle_disconnected(self, payload: dict[str, Any] | None = None) -> None:
        self.state.connected = False
        self.can_write = False
        self.pending_command = None
        expected = bool(payload and payload.get("expected"))
        self.status_label.setText("Disconnected" if expected else "Disconnected - reconnecting")
        message = "! disconnected" if expected else "! disconnected unexpectedly"
        if message != self.last_disconnect_message:
            self._append_raw(message)
            self.last_disconnect_message = message
        self._set_connected_controls(False)
        self._refresh_fields()

    def _handle_data(self, payload: bytes) -> None:
        lines = self.parser.feed(payload)
        if not lines:
            return

        for line in lines:
            self._append_raw(line)
            self._apply_line(line)

        self._refresh_fields()

    def _apply_line(self, line: str) -> None:
        self.state.last_update_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        self.state.last_update_monotonic = time.monotonic()
        self.state.last_response = line

        parts = [part.strip() for part in line.split(",") if part.strip()]
        if not parts:
            return

        message_type = parts[0].upper()
        pairs = parse_pairs(parts[1:])

        if message_type == "PONG":
            value = pairs.get("esp32_ms") or pairs.get("ms") or "alive"
            self.state.esp32_alive = value
            self._clear_pending_command("PONG")
            return

        if message_type == "ACK":
            self.state.last_response = line
            self._clear_pending_command("ACK")
            return

        if message_type == "STATUS":
            self._apply_status_pairs(pairs)
            self._clear_pending_command("STATUS")
            return

        if message_type == "$LOG":
            self._apply_log_line(line)
            return

        if message_type == "TEL":
            self._apply_tel_pairs(pairs)
            return

        if message_type == "CAN":
            self.state.can_rx_count += 1
            self.state.last_can_id = pairs.get("id", pairs.get("can_id", self.state.last_can_id))
            self.state.last_can_data = pairs.get("data", self.state.last_can_data)
            return

        if message_type == "GPS":
            self.state.gps_status = pairs.get("status", pairs.get("gps", self.state.gps_status))
            for key in ("gps_speed", "gps_speed_kph", "gps_kph", "speed"):
                if key in pairs:
                    self.state.gps_speed = pairs[key]
            self.state.latest_gps_sentence = pairs.get(
                "sentence", pairs.get("nmea", self.state.latest_gps_sentence)
            )
            return

        if message_type == "IMU":
            self.state.imu_speed = pairs.get(
                "speed", pairs.get("imu_speed", self.state.imu_speed)
            )
            return

        if message_type in {"ENV", "BME", "TEMP"}:
            self._apply_environment_pairs(pairs)
            return

        if message_type == "SD":
            self.state.sd_card_status = pairs.get("status", pairs.get("sd", line))
            return

        if message_type == "STM32":
            self.state.stm32_alive = pairs.get("alive", pairs.get("ms", "alive"))
            return

        if message_type == "ESP32":
            self.state.esp32_alive = pairs.get("alive", pairs.get("ms", "alive"))
            return

        self._apply_status_pairs(pairs)

    def _clear_pending_command(self, response_type: str) -> None:
        if self.pending_command is None:
            return
        command, _started_at = self.pending_command
        self.pending_command = None
        self._append_raw(f"! {response_type} received for last command: {command}")

    def _check_pending_command(self) -> None:
        if self.pending_command is None:
            return
        command, started_at = self.pending_command
        if time.monotonic() - started_at < 3:
            return
        self.pending_command = None
        self._append_raw(
            f"! no ACK/STATUS/PONG within 3s for {command}; BLE write succeeded, firmware response was not seen"
        )

    def _apply_tel_pairs(self, pairs: dict[str, str]) -> None:
        if "seq" in pairs:
            self.state.esp32_alive = f"seq {pairs['seq']}"
        if "age_ms" in pairs:
            self.state.stm32_alive = f"age {pairs['age_ms']} ms"

        if self._apply_shifted_compact_fields(pairs):
            self._apply_tel_status_pairs(pairs)
            return

        if "speed" in pairs:
            self.state.imu_speed = pairs["speed"]
        if "temp" in pairs:
            self.state.temperature = pairs["temp"]
        if "temperature" in pairs:
            self.state.temperature = pairs["temperature"]
        if "pressure" in pairs:
            self.state.pressure = pairs["pressure"]
        if "humidity" in pairs:
            self.state.humidity = pairs["humidity"]
        if "can" in pairs:
            self.state.last_can_data = f"CAN {pairs['can']}"
            with contextlib.suppress(ValueError):
                self.state.can_rx_count = int(pairs["can"])
        if "can_id" in pairs:
            self.state.last_can_id = pairs["can_id"]
        if "gps" in pairs:
            self.state.gps_status = pairs["gps"]
        if "gps_status" in pairs:
            self.state.gps_status = pairs["gps_status"]
        for key in ("gps_speed", "gps_speed_kph", "gps_kph"):
            if key in pairs:
                self.state.gps_speed = pairs[key]
        if "gps_sentence" in pairs:
            self.state.latest_gps_sentence = pairs["gps_sentence"]
        if "raw_len" in pairs:
            self.state.last_can_data = (
                f"{self.state.last_can_data} raw_len={pairs['raw_len']}"
            )

    def _apply_shifted_compact_fields(self, pairs: dict[str, str]) -> bool:
        speed = self._parse_float(pairs.get("speed"))
        compact_temp = self._parse_float(pairs.get("temp") or pairs.get("temperature"))
        if speed is None or compact_temp is None:
            return False

        if not (30000 <= speed <= 120000 and 0 <= compact_temp <= 100):
            return False

        self.state.pressure = pairs["speed"]
        self.state.humidity = pairs.get("temp", pairs.get("temperature", self.state.humidity))
        if self.state.temperature in {"-", self.state.humidity}:
            self.state.temperature = "-"
        if self.state.imu_speed == pairs["speed"]:
            self.state.imu_speed = "-"
        return True

    def _apply_tel_status_pairs(self, pairs: dict[str, str]) -> None:
        if "can" in pairs:
            self.state.last_can_data = f"CAN {pairs['can']}"
            with contextlib.suppress(ValueError):
                self.state.can_rx_count = int(pairs["can"])
        if "can_id" in pairs:
            self.state.last_can_id = pairs["can_id"]
        if "gps" in pairs:
            self.state.gps_status = pairs["gps"]
        if "gps_status" in pairs:
            self.state.gps_status = pairs["gps_status"]
        for key in ("gps_speed", "gps_speed_kph", "gps_kph"):
            if key in pairs:
                self.state.gps_speed = pairs[key]
        if "gps_sentence" in pairs:
            self.state.latest_gps_sentence = pairs["gps_sentence"]
        if "raw_len" in pairs:
            self.state.last_can_data = (
                f"{self.state.last_can_data} raw_len={pairs['raw_len']}"
            )

    def _apply_log_line(self, line: str) -> None:
        try:
            fields = next(csv.reader([line]))
        except csv.Error:
            return

        if self._is_extended_current_log_fields(fields):
            self._apply_extended_current_log_fields(fields)
            return

        if len(fields) >= 23:
            self._apply_legacy_log_fields(fields)
            return

        if len(fields) >= 21:
            self._apply_current_log_fields(fields)
            return

    def _apply_current_log_fields(self, fields: list[str]) -> None:
        self.state.esp32_alive = f"seq {fields[1]} uptime {fields[2]} ms"
        self.state.stm32_alive = "OK" if fields[4] == "1" else fields[4]

        self.state.imu_speed = fields[11]
        self.state.temperature = fields[12]
        self.state.pressure = fields[13]
        self.state.humidity = fields[14]
        self.state.sd_card_status = self._format_sd_status(fields[15])
        self.state.last_can_id = fields[16]
        self.state.last_can_data = f"{fields[17]},{fields[18]}"
        self.state.latest_gps_sentence = fields[20]
        self.state.gps_status = gps_status_from_sentence(fields[20])

    def _apply_extended_current_log_fields(self, fields: list[str]) -> None:
        self.state.esp32_alive = f"seq {fields[1]} uptime {fields[2]} ms"
        self.state.stm32_alive = "OK" if fields[4] == "1" else fields[4]

        self.state.imu_speed = fields[11]
        self.state.gps_speed = fields[12]
        self.state.temperature = fields[16]
        self.state.pressure = fields[17]
        self.state.humidity = fields[18]
        self.state.sd_card_status = self._format_sd_status(fields[19])
        self.state.last_can_id = fields[20]
        self.state.last_can_data = f"{fields[21]},{fields[22]}"
        if fields[23]:
            self.state.last_can_data = f"{self.state.last_can_data},{fields[23]}"
        self.state.latest_gps_sentence = fields[24]

        sentence_status = gps_status_from_sentence(fields[24])
        gps_block_status = fields[15]
        self.state.gps_status = (
            sentence_status
            if sentence_status != "-"
            else ("-" if gps_block_status in {"", "NONE"} else gps_block_status)
        )

    def _apply_legacy_log_fields(self, fields: list[str]) -> None:
        self.state.esp32_alive = f"uptime {fields[2]} ms"
        self.state.stm32_alive = "OK" if fields[4] == "1" else fields[4]
        self.state.esp32_alive = "OK" if fields[6] == "1" else self.state.esp32_alive

        with contextlib.suppress(ValueError):
            self.state.can_rx_count = int(fields[8])

        self.state.imu_speed = fields[13]
        self.state.temperature = fields[14]
        self.state.pressure = fields[15]
        self.state.humidity = fields[16]
        self.state.sd_card_status = self._format_sd_status(fields[17])
        self.state.last_can_id = fields[18]
        self.state.last_can_data = f"{fields[19]},{fields[20]}"
        self.state.latest_gps_sentence = fields[22]
        self.state.gps_status = gps_status_from_sentence(fields[22])

    def _apply_status_pairs(self, pairs: dict[str, str]) -> None:
        source_parts: list[str] = []
        for key, label in (("stm32", "src"), ("spi_task", "spi"), ("age_ms", "age")):
            if key in pairs:
                value = f"{pairs[key]} ms" if key == "age_ms" else pairs[key]
                source_parts.append(f"{label}={value}")
        if source_parts:
            self.state.stm32_alive = " ".join(source_parts)

        esp32_parts: list[str] = []
        for key, label in (
            ("esp32", "esp32"),
            ("ble", "ble"),
            ("seq", "seq"),
            ("mode", "mode"),
            ("rate_ms", "rate"),
        ):
            if key in pairs:
                value = f"{pairs[key]} ms" if key == "rate_ms" else pairs[key]
                esp32_parts.append(f"{label}={value}")
        if esp32_parts:
            self.state.esp32_alive = " ".join(esp32_parts)

        if "can" in pairs:
            self.state.last_can_data = f"CAN {pairs['can']}"
        if "sd" in pairs:
            self.state.sd_card_status = pairs["sd"]
        if "gps" in pairs:
            self.state.gps_status = pairs["gps"]
        for key in ("gps_speed", "gps_speed_kph", "gps_kph"):
            if key in pairs:
                self.state.gps_speed = pairs[key]
        self._apply_environment_pairs(pairs)

    @staticmethod
    def _format_sd_status(value: str) -> str:
        return "OK" if value == "0" else value

    @staticmethod
    def _parse_float(value: str | None) -> float | None:
        if value is None:
            return None
        with contextlib.suppress(ValueError):
            return float(value)
        return None

    @staticmethod
    def _is_extended_current_log_fields(fields: list[str]) -> bool:
        if len(fields) < 25:
            return False
        return (
            DashboardWindow._parse_float(fields[5]) is not None
            and DashboardWindow._parse_float(fields[16]) is not None
            and DashboardWindow._parse_float(fields[17]) is not None
            and DashboardWindow._parse_float(fields[18]) is not None
        )

    def _apply_environment_pairs(self, pairs: dict[str, str]) -> None:
        if "temp" in pairs:
            self.state.temperature = pairs["temp"]
        if "temperature" in pairs:
            self.state.temperature = pairs["temperature"]
        if "pressure" in pairs:
            self.state.pressure = pairs["pressure"]
        if "humidity" in pairs:
            self.state.humidity = pairs["humidity"]

    def _refresh_fields(self) -> None:
        connection = "Connected" if self.state.connected else "Disconnected"
        if self.state.connected:
            connection = f"{connection} - {self.state.device_name}"

        environment = (
            f"{self.state.temperature} / {self.state.pressure} / {self.state.humidity}"
        )

        values = {
            "connection": connection,
            "last_update_time": self.state.last_update_time,
            "seconds_since_last_update": self.state.seconds_since_last_update,
            "can_rx_count": str(self.state.can_rx_count),
            "last_can_id": self.state.last_can_id,
            "last_can_data": self.state.last_can_data,
            "gps_status": self.state.gps_status,
            "gps_speed": self.state.gps_speed,
            "latest_gps_sentence": self.state.latest_gps_sentence,
            "imu_speed": self.state.imu_speed,
            "environment": environment,
            "sd_card_status": self.state.sd_card_status,
            "stm32_alive": self.state.stm32_alive,
            "esp32_alive": self.state.esp32_alive,
            "last_response": self.state.last_response,
        }

        for key, value in values.items():
            self.field_labels[key].setText(value)

    def _refresh_elapsed_time(self) -> None:
        self.field_labels["seconds_since_last_update"].setText(
            self.state.seconds_since_last_update
        )

    def _append_raw(self, line: str) -> None:
        now = time.monotonic()
        timestamp = datetime.now().strftime("%H:%M:%S")
        self.raw_entries.append((now, f"[{timestamp}] {line}"))
        self._prune_raw_entries(now)
        self.raw_text.setPlainText("\n".join(entry for _created_at, entry in self.raw_entries))
        scrollbar = self.raw_text.verticalScrollBar()
        scrollbar.setValue(scrollbar.maximum())

    def _prune_raw_entries(self, now: float) -> None:
        cutoff = now - RAW_TERMINAL_RETENTION_SECONDS
        while self.raw_entries and self.raw_entries[0][0] < cutoff:
            self.raw_entries.popleft()

    def _set_connected_controls(self, connected: bool) -> None:
        self.scan_button.setEnabled(not connected)
        self.connect_button.setEnabled(not connected)
        self.disconnect_button.setEnabled(connected)
        command_enabled = connected and self.can_write
        self.command_entry.setEnabled(command_enabled)
        self.send_button.setEnabled(command_enabled)
        self.rtc_datetime.setEnabled(command_enabled)
        for button in self.command_buttons:
            button.setEnabled(command_enabled)

    def closeEvent(self, event: Any) -> None:
        self.worker.shutdown()
        event.accept()
