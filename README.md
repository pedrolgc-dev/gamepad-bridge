<div align="center">

# Gamepad Bridge

**A simple, lightweight android app that reads a gamepad plugged into the phone via USB-C and sends its input over Bluetooth to a PC, so the phone behaves like a normal Bluetooth controller, giving new purpose to gamepads that are USB-C only.**

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white&style=flat-square)
![Android](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white&style=flat-square)
![Bluetooth HID](https://img.shields.io/badge/Bluetooth-HID-0082FC?logo=bluetooth&logoColor=white&style=flat-square)
![License](https://img.shields.io/badge/License-MIT-lightgrey?style=flat-square)

<br>

<img src="docs/screenshot-main.png" width="260" alt="Main screen">
&nbsp;&nbsp;

</div>

---

## Features

| | |
| **Live controller view** | Buttons, sticks, D-pad and triggers light up as you press them |
| **Standard HID gamepad** | The PC sees the phone as a normal Bluetooth gamepad |
| **Controller detection** | Shows the name of the connected gamepad and the connection status |
| **No dependencies** | Pure Kotlin and the Android SDK |

**Supported inputs:** 16 buttons, 1 hat switch (D-pad), 2 analog sticks, 2 analog triggers.

---

## How it works

1. Android delivers the gamepad's key and motion events to the app.
2. The app stores them in a single state object and packs it into a 9-byte HID report.
3. Using the `BluetoothHidDevice` API, the phone registers as a gamepad and sends the report to the paired PC.

---

## Getting started

**Requirements:** A phone running Android 9 or later (the app opens on Android 7+, but Bluetooth sending needs Android 9+), and a USB-C gamepad.

1. Run the app on your phone (use wireless debugging, since the USB-C port is occupied by the gamepad).
2. Plug in the gamepad and grant the Bluetooth permissions.
3. Tap **Make Discoverable**, then pair the phone from your PC's Bluetooth settings.
4. Test the gamepad on Windows with `joy.cpl`.

---

## Project structure

```
app/src/main/
├── java/com/example/gamepadbridge/
│   ├── MainActivity.kt     Screen, permissions, event dispatch
│   ├── GamepadInput.kt     Reads buttons and axes from the controller
│   ├── GamepadState.kt     Current state and HID report packing
│   ├── GamepadView.kt      Custom view that draws the controller
│   └── HidGamepad.kt       Bluetooth HID device and report sending
└── res/                    Layout, colors, strings, drawables
```

---

## Limitations

- The app must stay open on screen, because Android only delivers gamepad events to the foreground app.
- Bluetooth HID support varies between phone manufacturers.

---

## License

Released under the MIT License.
