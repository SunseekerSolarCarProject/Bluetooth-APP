import csv
import time
from dataclasses import dataclass, field


@dataclass
class TelemetryState:
    connected: bool = False
    device_name: str = "-"
    last_update_time: str = "-"
    last_update_monotonic: float | None = None
    can_rx_count: int = 0
    last_can_id: str = "-"
    last_can_data: str = "-"
    gps_status: str = "-"
    latest_gps_sentence: str = "-"
    imu_speed: str = "-"
    temperature: str = "-"
    pressure: str = "-"
    humidity: str = "-"
    sd_card_status: str = "-"
    stm32_alive: str = "-"
    esp32_alive: str = "-"
    last_response: str = "-"
    raw_lines: list[str] = field(default_factory=list)

    @property
    def seconds_since_last_update(self) -> str:
        if self.last_update_monotonic is None:
            return "-"
        return f"{time.monotonic() - self.last_update_monotonic:.1f}s"


class TelemetryParser:
    def __init__(self) -> None:
        self.buffer = ""

    def feed(self, data: bytes) -> list[str]:
        self.buffer += data.decode("utf-8", errors="replace")
        lines: list[str] = []

        while "\n" in self.buffer or "\r" in self.buffer:
            newline_positions = [
                pos for pos in (self.buffer.find("\n"), self.buffer.find("\r")) if pos >= 0
            ]
            split_at = min(newline_positions)
            line = self.buffer[:split_at].strip()
            self.buffer = self.buffer[split_at + 1 :]
            self.buffer = self.buffer.lstrip("\r\n")
            if line:
                lines.append(line)

        record_lines = self._split_records(self.buffer)
        if record_lines and (
            len(record_lines) > 1 or self._looks_complete_record(record_lines[0])
        ):
            lines.extend(record_lines)
            self.buffer = ""
            return lines

        if len(self.buffer) > 512:
            record_lines = self._split_records(self.buffer)
            if record_lines:
                lines.extend(record_lines)
                self.buffer = ""
            else:
                lines.append(self.buffer.strip())
                self.buffer = ""

        return lines

    @staticmethod
    def _split_records(text: str) -> list[str]:
        text = text.strip()
        if not text:
            return []

        starts: list[int] = []
        in_quotes = False
        index = 0

        while index < len(text):
            char = text[index]
            if char == '"':
                in_quotes = not in_quotes
            elif not in_quotes and (
                text.startswith("$LOG,", index) or text.startswith("TEL,", index)
            ):
                starts.append(index)
            index += 1

        if not starts:
            return []

        records: list[str] = []
        for record_index, start in enumerate(starts):
            end = starts[record_index + 1] if record_index + 1 < len(starts) else len(text)
            record = text[start:end].strip()
            if record:
                records.append(record)
        return records

    @staticmethod
    def _looks_complete_record(record: str) -> bool:
        if record.startswith("TEL,"):
            return ",gps=" in record or ",gps_status=" in record

        if record.startswith("$LOG,"):
            try:
                return len(next(csv.reader([record]))) >= 23
            except csv.Error:
                return False

        return False


def gps_status_from_sentence(sentence: str) -> str:
    if not sentence:
        return "-"

    if sentence.startswith(("$GNGGA", "$GPGGA")):
        parts = sentence.split(",")
        if len(parts) > 6:
            return "FIX" if parts[6] not in {"", "0"} else "NO_FIX"

    if sentence.startswith(("$GNGSA", "$GPGSA")):
        parts = sentence.split(",")
        if len(parts) > 2:
            return "FIX" if parts[2] not in {"", "1"} else "NO_FIX"

    return "seen"


def parse_pairs(parts: list[str]) -> dict[str, str]:
    pairs: dict[str, str] = {}
    for part in parts:
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        pairs[key.strip().casefold()] = value.strip()
    return pairs
