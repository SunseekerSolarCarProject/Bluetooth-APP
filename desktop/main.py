import sys
from pathlib import Path

from PyQt6.QtWidgets import QApplication

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from desktop.gui import DashboardWindow


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("ESP32 BLE Telemetry")
    window = DashboardWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
