import asyncio
import concurrent.futures
import contextlib
import queue
import threading
from typing import Any

from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic
from bleak.backends.device import BLEDevice

from app_config import (
    BLUETOOTH_SIG_SUFFIX,
    BLE_COMMAND_CHUNK_SIZE,
    COMMON_SERIAL_UUIDS,
    NORDIC_UART_RX,
    NORDIC_UART_TX,
    SERVICE_CHANGED,
)


class BleWorker:
    def __init__(self, events: queue.Queue[tuple[str, Any]]) -> None:
        self.events = events
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self._run_loop, daemon=True)
        self.client: BleakClient | None = None
        self.notify_characteristics: list[BleakGATTCharacteristic] = []
        self.write_characteristic: BleakGATTCharacteristic | None = None
        self.write_with_response = True
        self.device: BLEDevice | None = None
        self.want_connected = False
        self.reconnect_task: asyncio.Task[None] | None = None
        self.user_disconnect = False

    def start(self) -> None:
        self.thread.start()

    def scan(self, timeout: float = 8.0) -> None:
        self._submit(self._scan(timeout))

    def connect(self, device: BLEDevice) -> None:
        self._submit(self._connect(device))

    def disconnect(self) -> None:
        self._submit(self._user_disconnect())

    def send(self, command: str) -> None:
        self._submit(self._send(command))

    def shutdown(self) -> None:
        future = asyncio.run_coroutine_threadsafe(self._shutdown(), self.loop)
        with contextlib.suppress(Exception):
            future.result(timeout=2)
        self.thread.join(timeout=2)

    def _run_loop(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def _submit(self, coroutine: Any) -> None:
        future = asyncio.run_coroutine_threadsafe(coroutine, self.loop)
        future.add_done_callback(self._handle_done)

    def _handle_done(self, future: concurrent.futures.Future[Any]) -> None:
        with contextlib.suppress(concurrent.futures.CancelledError):
            error = future.exception()
            if error:
                self.events.put(("error", str(error)))

    async def _shutdown(self) -> None:
        self.want_connected = False
        self.user_disconnect = True
        await self._disconnect(emit_event=False)
        self.loop.stop()

    async def _scan(self, timeout: float) -> None:
        self.events.put(("status", "Scanning for BLE devices..."))
        devices = await BleakScanner.discover(timeout=timeout)
        devices = [device for device in devices if device.name or device.address]
        devices.sort(key=lambda device: ((device.name or "Unknown").casefold(), device.address))
        self.events.put(("devices", devices))
        self.events.put(("status", f"Found {len(devices)} BLE device(s)."))

    async def _connect(self, device: BLEDevice) -> None:
        self.device = device
        self.want_connected = True
        self.user_disconnect = False
        current_task = asyncio.current_task()
        if (
            self.reconnect_task
            and not self.reconnect_task.done()
            and self.reconnect_task is not current_task
        ):
            self.reconnect_task.cancel()
        await self._disconnect(emit_event=False)
        self.events.put(("status", f"Connecting to {device.name or device.address}..."))

        client = BleakClient(device, disconnected_callback=self._on_disconnect)
        await client.connect()
        if not client.is_connected:
            raise RuntimeError("Failed to connect.")

        try:
            self.client = client
            notify_characteristics = self._notify_characteristics(client)
            self.write_characteristic = self._choose_write_characteristic(client)
            self._log_characteristics(client)

            if not notify_characteristics:
                raise RuntimeError(
                    "Connected, but no notify/indicate characteristic was found."
                )

            notify_errors: list[str] = []
            subscribed: list[BleakGATTCharacteristic] = []
            for characteristic in notify_characteristics:
                try:
                    await client.start_notify(characteristic, self._on_data)
                    subscribed.append(characteristic)
                    self.events.put(
                        (
                            "log",
                            (
                                "! subscribed to notify "
                                f"{characteristic.uuid} handle={characteristic.handle}"
                            ),
                        )
                    )
                except Exception as exc:
                    notify_errors.append(
                        f"{characteristic.uuid} handle={characteristic.handle}: {exc}"
                    )
                    self.events.put(
                        (
                            "log",
                            (
                                "! notify failed on "
                                f"{characteristic.uuid} handle={characteristic.handle}: {exc}"
                            ),
                        )
                    )

            self.notify_characteristics = subscribed

            if not self.notify_characteristics:
                detail = "; ".join(notify_errors)
                raise RuntimeError(
                    "Could not subscribe to any notify/indicate characteristic. "
                    "If the ESP32 requires a secured characteristic, remove/re-pair it "
                    f"in Windows Bluetooth settings and retry. Details: {detail}"
                )
            self.events.put(
                (
                    "log",
                    f"! active notify subscriptions: {len(self.notify_characteristics)}",
                )
            )

            if self.write_characteristic is not None:
                mode = "with response" if self.write_with_response else "without response"
                self.events.put(
                    (
                        "log",
                        (
                            "! using write "
                            f"{self.write_characteristic.uuid} "
                            f"handle={self.write_characteristic.handle} {mode}"
                        ),
                    )
                )

            self.events.put(
                (
                    "connected",
                    {
                        "name": device.name or "Unknown",
                        "address": device.address,
                        "can_write": self.write_characteristic is not None,
                    },
                )
            )
        except Exception:
            with contextlib.suppress(Exception):
                await client.disconnect()
            self.client = None
            self.notify_characteristics = []
            self.write_characteristic = None
            self.write_with_response = True
            raise

    async def _user_disconnect(self) -> None:
        self.want_connected = False
        self.user_disconnect = True
        if self.reconnect_task and not self.reconnect_task.done():
            self.reconnect_task.cancel()
        await self._disconnect()

    async def _disconnect(self, emit_event: bool = True) -> None:
        client = self.client
        if not client:
            return

        with contextlib.suppress(Exception):
            if client.is_connected:
                for characteristic in self.notify_characteristics:
                    await client.stop_notify(characteristic)
        with contextlib.suppress(Exception):
            if client.is_connected:
                await client.disconnect()

        self.client = None
        self.notify_characteristics = []
        self.write_characteristic = None
        self.write_with_response = True
        if emit_event:
            self.events.put(("disconnected", {"expected": self.user_disconnect}))

    async def _send(self, command: str) -> None:
        client = self.client
        if not client or not client.is_connected:
            raise RuntimeError("Not connected to a BLE device.")
        if self.write_characteristic is None:
            raise RuntimeError("No writable characteristic was found on this device.")

        payload = command.strip()
        if not payload:
            return
        await self._write_command(client, payload)
        self.events.put(("sent", payload))

    async def _write_command(self, client: BleakClient, command: str) -> None:
        data = f"{command}\n".encode("utf-8")
        chunks = [
            data[index : index + BLE_COMMAND_CHUNK_SIZE]
            for index in range(0, len(data), BLE_COMMAND_CHUNK_SIZE)
        ]

        if len(chunks) > 1:
            self.events.put(
                (
                    "log",
                    f"! command is {len(data)} bytes; sending in {len(chunks)} BLE chunks",
                )
            )

        for chunk in chunks:
            await client.write_gatt_char(
                self.write_characteristic,
                chunk,
                response=self.write_with_response,
            )
            await asyncio.sleep(0.03)

    def _on_data(self, sender: BleakGATTCharacteristic, data: bytearray) -> None:
        self.events.put(("data", bytes(data)))

    def _on_disconnect(self, client: BleakClient) -> None:
        if self.client is client:
            self.client = None
            self.notify_characteristics = []
            self.write_characteristic = None
            self.write_with_response = True
            expected = self.user_disconnect or not self.want_connected
            self.events.put(("disconnected", {"expected": expected}))
            if not expected:
                self._schedule_reconnect()

    def _schedule_reconnect(self) -> None:
        if self.reconnect_task and not self.reconnect_task.done():
            return
        self.reconnect_task = self.loop.create_task(self._reconnect_loop())

    async def _reconnect_loop(self) -> None:
        delays = (1, 2, 4, 8)
        attempt = 0
        while self.want_connected and self.device is not None:
            delay = delays[min(attempt, len(delays) - 1)]
            self.events.put(("status", f"Disconnected. Reconnecting in {delay}s..."))
            await asyncio.sleep(delay)

            if not self.want_connected or self.device is None:
                return

            attempt += 1
            try:
                await self._connect(self.device)
                return
            except Exception as exc:
                self.events.put(("log", f"! reconnect attempt failed: {exc}"))

    def _notify_characteristics(
        self, client: BleakClient
    ) -> list[BleakGATTCharacteristic]:
        choices: list[BleakGATTCharacteristic] = []
        for service in client.services:
            for characteristic in service.characteristics:
                properties = set(characteristic.properties)
                if "notify" in properties or "indicate" in properties:
                    if characteristic.uuid.casefold() == SERVICE_CHANGED:
                        continue
                    choices.append(characteristic)

        return self._sort_characteristics(choices, preferred_uuid=NORDIC_UART_TX)

    def _choose_write_characteristic(
        self, client: BleakClient
    ) -> BleakGATTCharacteristic | None:
        choices: list[BleakGATTCharacteristic] = []
        for service in client.services:
            for characteristic in service.characteristics:
                properties = set(characteristic.properties)
                if "write" in properties or "write-without-response" in properties:
                    choices.append(characteristic)

        sorted_choices = self._sort_characteristics(choices, preferred_uuid=NORDIC_UART_RX)
        selected = sorted_choices[0] if sorted_choices else None
        if selected is not None:
            self.write_with_response = "write-without-response" not in selected.properties
        return selected

    @staticmethod
    def _sort_characteristics(
        characteristics: list[BleakGATTCharacteristic], preferred_uuid: str
    ) -> list[BleakGATTCharacteristic]:
        def sort_key(characteristic: BleakGATTCharacteristic) -> tuple[int, str]:
            uuid = characteristic.uuid.casefold()
            if uuid == preferred_uuid:
                return (0, uuid)
            if uuid in COMMON_SERIAL_UUIDS:
                return (1, uuid)
            if not is_bluetooth_sig_uuid(uuid):
                return (2, uuid)
            return (3, uuid)

        return sorted(characteristics, key=sort_key)

    def _log_characteristics(self, client: BleakClient) -> None:
        self.events.put(("log", "! discovered writable/notify characteristics:"))
        for service in client.services:
            for characteristic in service.characteristics:
                properties = set(characteristic.properties)
                interesting = properties.intersection(
                    {"notify", "indicate", "write", "write-without-response"}
                )
                if not interesting:
                    continue
                props = ",".join(characteristic.properties)
                kind = "standard" if is_bluetooth_sig_uuid(characteristic.uuid) else "app"
                self.events.put(
                    (
                        "log",
                        (
                            f"!   {characteristic.uuid} handle={characteristic.handle} "
                            f"props={props} {kind}"
                        ),
                    )
                )


def is_bluetooth_sig_uuid(uuid: str) -> bool:
    return uuid.casefold().endswith(BLUETOOTH_SIG_SUFFIX)
