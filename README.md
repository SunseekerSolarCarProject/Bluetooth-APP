# ESP32 BLE Telemetry Desktop App

Simple PyQt6 desktop dashboard for receiving telemetry from an ESP32 BLE device and sending control commands back to the firmware.

## Features

- Scan for nearby BLE devices.
- Connect to an ESP32 telemetry device.
- Display parsed telemetry values:
  - connection status
  - last update time
  - seconds since last update
  - CAN status/count
  - GPS status/latest sentence
  - IMU speed
  - temperature, pressure, humidity
  - SD card status
  - telemetry source freshness and ESP32 sequence/status
- Show raw BLE terminal output.
- Send firmware commands such as `PING`, `GET_STATUS`, `SET_MODE,COMPACT`, `FIELDS,STCG`, and `SET_RTC,now`.
- Automatically reconnect after unexpected BLE disconnects.

## Requirements

- Python 3.11 or newer recommended
- Bluetooth LE support on the desktop
- ESP32 firmware exposing BLE notify/write characteristics

Install Python dependencies:

```powershell
python -m pip install -r requirements.txt
```

## Run

```powershell
python main.py
```

Then:

1. Click `Scan`.
2. Select the ESP32 BLE device.
3. Click `Connect`.
4. Watch telemetry in the dashboard and raw terminal.

## Expected BLE Behavior

The app looks for notify and write characteristics and prefers custom/application UUIDs over standard Bluetooth UUIDs.

Known supported UUIDs include Nordic UART:

```text
RX/write:  6e400002-b5a3-f393-e0a9-e50e24dcca9e
TX/notify: 6e400003-b5a3-f393-e0a9-e50e24dcca9e
```

The current ESP32 firmware also appears to expose custom telemetry/control characteristics like:

```text
02000010-236d-4f93-9145-7b5a2032548f
03000010-236d-4f93-9145-7b5a2032548f
```

Commands are sent as UTF-8 text ending with `\n`. Long commands are split into 20-byte BLE chunks.

## Telemetry Formats

Compact telemetry:

```text
TEL,v=1,seq=2189,age_ms=12,raw_len=180,speed=0.00,temp=23.72,can=0,gps=NO_FIX
```

Raw log telemetry:

```text
$LOG,354,360745,"2026-05-16 17:56:44",1,955,256,164,0,-61,-183,0.00,22.00,98643.80,50.04,0,0x000,0,0,"","$GNGGA..."
```

The current raw log format uses one firmware timestamp. Older two-timestamp `$LOG` rows are still parsed as a compatibility fallback.

## Commands

Common commands:

```text
GET_STATUS
SET_RATE,1000
SET_RATE,500
SET_MODE,RAW
SET_MODE,COMPACT
FIELDS,STCG
PING
START_LOG
STOP_LOG
CALIBRATE_IMU
SET_RTC
CLEAR_ERRORS
```

The `SET_RTC,now` button sends the desktop's current local time. The `SET_RTC,selected` control sends the date/time chosen in the app. Both use:

```text
SET_RTC,YYYY-MM-DD,HH:MM:SS
```

Field aliases for `FIELDS,STCG`:

```text
S = speed
T = temp
C = CAN
G = GPS
I = IMU
E = env
D = SD
A = all fields
```

## Project Files

- `main.py` - application entry point
- `gui.py` - PyQt6 user interface
- `ble_worker.py` - BLE scan/connect/read/write logic
- `telemetry.py` - telemetry parsing and state
- `app_config.py` - UUIDs and command definitions
- `requirements.txt` - Python dependencies

## License

MIT License. See `LICENSE`.
