import sys

from PyQt6.QtWidgets import QApplication

from gui import DashboardWindow


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("ESP32 BLE Telemetry")
    window = DashboardWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
