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
    RECORD_PREFIXES = ("$LOG,", "TEL,", "STATUS,")

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
                lines.extend(self._split_complete_records(line))

        record_lines, remainder = self._extract_records(self.buffer)
        lines.extend(record_lines)
        self.buffer = remainder

        if len(self.buffer) > 4096 and not self._record_starts(self.buffer):
            lines.append(self.buffer.strip())
            self.buffer = ""

        return lines

    def _split_complete_records(self, text: str) -> list[str]:
        records, remainder = self._extract_records(text, force_last=True)
        if remainder.strip():
            records.append(remainder.strip())
        return records

    def _extract_records(self, text: str, force_last: bool = False) -> tuple[list[str], str]:
        text = text.strip()
        if not text:
            return [], ""

        starts = self._record_starts(text)
        if not starts:
            return [], text

        if starts[0] > 0:
            leading = text[: starts[0]].strip()
            records = [leading] if leading else []
        else:
            records = []

        for record_index, start in enumerate(starts):
            is_last = record_index == len(starts) - 1
            end = starts[record_index + 1] if not is_last else len(text)
            record = text[start:end].strip()
            if not record:
                continue
            if not is_last or force_last or self._looks_complete_record(record):
                records.append(record)
                continue
            return records, record

        return records, ""

    @staticmethod
    def _record_starts(text: str) -> list[int]:
        starts: list[int] = []
        in_quotes = False
        index = 0

        while index < len(text):
            char = text[index]
            if char == '"':
                in_quotes = not in_quotes
            elif not in_quotes and any(
                text.startswith(prefix, index) for prefix in TelemetryParser.RECORD_PREFIXES
            ):
                starts.append(index)
            index += 1

        return starts

    @staticmethod
    def _looks_complete_record(record: str) -> bool:
        if record.startswith("TEL,"):
            return ",gps=" in record or ",gps_status=" in record

        if record.startswith("STATUS,"):
            terminal_markers = (
                ",stm_drop=",
                ",cmd_drop=",
                ",ble_drop=",
                ",seq_jumps=",
                ",last_ble_notify_ms=",
            )
            return any(marker in record for marker in terminal_markers)

        if record.startswith("$LOG,"):
            try:
                return len(next(csv.reader([record]))) >= 21
            except csv.Error:
                return False

        return False


def gps_status_from_sentence(sentence: str) -> str:
    if not sentence or sentence in {"-", "NO_GPS", "NO_DATA"}:
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
