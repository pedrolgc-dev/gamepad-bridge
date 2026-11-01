package com.example.gamepadbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/**
 * Exposes the phone as a Bluetooth gamepad (HID Device profile, API 28+)
 * and sends GamepadState reports to the PC.
 *
 * Permissions: on Android 12+ BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE must be
 * granted before calling start() (handled by MainActivity). On Android 9–11
 * BLUETOOTH and BLUETOOTH_ADMIN in manifest are sufficient.
 */
@RequiresApi(Build.VERSION_CODES.P) // BluetoothHidDevice is only available on Android 9+ (API 28)
@SuppressLint("MissingPermission")
class HidGamepad(
    private val context: Context,
    private val gamepadState: GamepadState,
    private val onStatus: (String) -> Unit
) : GamepadBridgeController {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastSentVersion = -1L
    private var lastGuideState = false
    private var isSendScheduled = false
    private var lastSendTimeMs = 0L

    /** Invoked with true when PC connects and false when it disconnects (used for status indicator). */
    override var onConnectionChanged: ((Boolean) -> Unit)? = null

    private fun getDeviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
    }

    // ---------------------------------------------------------------
    // HID profile callbacks
    // ---------------------------------------------------------------
    private val callback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) setAdapterName()
            onStatus(
                if (registered) "Xbox Wireless Controller registered. Pair from your PC."
                else "Gamepad registration removed."
            )
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, connState: Int) {
            when (connState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    lastSentVersion = -1L // force sending current state
                    lastGuideState = false
                    onStatus("Connected to ${getDeviceName(device)}")
                    onConnectionChanged?.invoke(true)
                    startSending()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device) host = null
                    stopSending()
                    onStatus("Disconnected.")
                    onConnectionChanged?.invoke(false)
                }
            }
        }

        // The PC can explicitly request current report state.
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            try {
                when (id.toInt()) {
                    GUIDE_REPORT_ID -> hid?.replyReport(device, type, id, gamepadState.toGuideReport())
                    BATTERY_REPORT_ID -> hid?.replyReport(device, type, id, byteArrayOf(0xFF.toByte()))
                    else -> hid?.replyReport(device, type, id, gamepadState.toReport())
                }
            } catch (_: Exception) {
            }
        }

        // Incoming reports from PC (e.g. rumble/effects).
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            try {
                hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (_: Exception) {
            }
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // Rumble and haptics data over interrupt channel
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profileType: Int, proxy: BluetoothProfile) {
            if (profileType != BluetoothProfile.HID_DEVICE) return
            val device = proxy as? BluetoothHidDevice ?: return
            hid = device

            try {
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    DEVICE_NAME,
                    DEVICE_NAME,
                    "Microsoft",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD,
                    DESCRIPTOR
                )

                setAdapterName()

                val ok = device.registerApp(sdp, null, null, context.mainExecutor, callback)
                if (!ok) onStatus("Failed to register gamepad.")
            } catch (e: Exception) {
                onStatus("Error registering gamepad: ${e.localizedMessage}")
            }
        }

        override fun onServiceDisconnected(profileType: Int) {
            if (profileType == BluetoothProfile.HID_DEVICE) hid = null
        }
    }

    fun setAdapterName() {
        try {
            val a = adapter ?: return
            if (a.name != DEVICE_NAME) {
                a.name = DEVICE_NAME
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------
    override fun start() {
        try {
            val a = adapter
            if (a == null || !a.isEnabled) {
                onStatus("Bluetooth unavailable or turned off.")
                return
            }
            setAdapterName()
            a.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            onStatus("Bluetooth HID error: ${e.localizedMessage}")
        }
    }

    override fun stop() {
        stopSending()
        try {
            hid?.let {
                it.unregisterApp()
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
            }
        } catch (_: Exception) {
        }
        hid = null
        host = null
    }

    /** Already paired devices (name, device). */
    override fun bondedDevices(): List<Pair<String, BluetoothDevice>> {
        val a = adapter ?: return emptyList()
        return try {
            a.bondedDevices.orEmpty().map { dev ->
                getDeviceName(dev) to dev
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Connects to a PC already paired with the phone. */
    override fun connectTo(device: BluetoothDevice) {
        try {
            hid?.connect(device)
        } catch (e: Exception) {
            onStatus("Error connecting to device: ${e.localizedMessage}")
        }
    }

    // ---------------------------------------------------------------
    // Demand-driven transmission with 125 Hz (~8 ms) rate-limiting
    // ---------------------------------------------------------------
    override fun notifyStateChanged() {
        if (host == null || hid == null) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastSendTimeMs
        if (elapsed >= SEND_INTERVAL_MS) {
            sendNow()
        } else if (!isSendScheduled) {
            isSendScheduled = true
            handler.postDelayed(sendTask, SEND_INTERVAL_MS - elapsed)
        }
    }

    private val sendTask = Runnable {
        isSendScheduled = false
        sendNow()
    }

    private fun sendNow() {
        val h = hid ?: return
        val d = host ?: return
        if (gamepadState.version != lastSentVersion) {
            lastSentVersion = gamepadState.version
            lastSendTimeMs = System.currentTimeMillis()
            try {
                h.sendReport(d, REPORT_ID, gamepadState.toReport())
                val guide = gamepadState.isPressed(Btn.MODE)
                if (guide != lastGuideState) {
                    lastGuideState = guide
                    h.sendReport(d, GUIDE_REPORT_ID, gamepadState.toGuideReport())
                }
            } catch (_: SecurityException) {
                onStatus("Permission error when sending Bluetooth data.")
                stopSending()
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun startSending() {
        stopSending()
        notifyStateChanged()
    }

    private fun stopSending() {
        handler.removeCallbacks(sendTask)
        isSendScheduled = false
    }

    companion object {
        const val DEVICE_NAME = "Xbox Wireless Controller"
        const val REPORT_ID = 1
        const val GUIDE_REPORT_ID = 2
        const val RUMBLE_REPORT_ID = 3
        const val BATTERY_REPORT_ID = 4
        private const val SEND_INTERVAL_MS = 8L

        /**
         * Official Microsoft Xbox Wireless Controller (Model 1708, Bluetooth) HID Report Descriptor.
         * Report ID 1 (15 bytes):
         *   - Left Stick (X, Y): 16-bit unsigned (0..65535, center = 32768)
         *   - Right Stick (Rx, Ry): 16-bit unsigned (0..65535, center = 32768)
         *   - Left Trigger (Z): 10-bit unsigned (0..1023) + 6-bit padding
         *   - Right Trigger (Rz): 10-bit unsigned (0..1023) + 6-bit padding
         *   - Hat Switch (D-Pad): 4 bits (1..8, 0=neutral) + 4-bit padding
         *   - Buttons: 10 buttons (A, B, X, Y, LB, RB, View, Menu, LSB, RSB) + 6-bit padding
         * Report ID 2 (1 byte): Xbox Guide Button
         * Report ID 3: Rumble / Force feedback output
         * Report ID 4 (1 byte): Battery strength input
         */
        private val DESCRIPTOR: ByteArray = intArrayOf(
            // Gamepad Collection (Report ID 1)
            0x05, 0x01,        // Usage Page (Generic Desktop Ctrls)
            0x09, 0x05,        // Usage (Game Pad)
            0xA1, 0x01,        // Collection (Application)
            0x85, 0x01,        //   Report ID (1)
            0x09, 0x01,        //   Usage (Pointer)
            0xA1, 0x00,        //   Collection (Physical)
            0x09, 0x30,        //     Usage (X) - Left Stick X
            0x09, 0x31,        //     Usage (Y) - Left Stick Y
            0x15, 0x00,        //     Logical Minimum (0)
            0x27, 0xFF, 0xFF, 0x00, 0x00, // Logical Maximum (65535)
            0x95, 0x02,        //     Report Count (2)
            0x75, 0x10,        //     Report Size (16)
            0x81, 0x02,        //     Input (Data,Var,Abs)
            0xC0,              //   End Collection
            0x09, 0x01,        //   Usage (Pointer)
            0xA1, 0x00,        //   Collection (Physical)
            0x09, 0x33,        //     Usage (Rx) - Right Stick X
            0x09, 0x34,        //     Usage (Ry) - Right Stick Y
            0x15, 0x00,        //     Logical Minimum (0)
            0x27, 0xFF, 0xFF, 0x00, 0x00, // Logical Maximum (65535)
            0x95, 0x02,        //     Report Count (2)
            0x75, 0x10,        //     Report Size (16)
            0x81, 0x02,        //     Input (Data,Var,Abs)
            0xC0,              //   End Collection
            0x05, 0x01,        //   Usage Page (Generic Desktop Ctrls)
            0x09, 0x32,        //   Usage (Z) - Left Trigger
            0x15, 0x00,        //   Logical Minimum (0)
            0x26, 0xFF, 0x03,  //   Logical Maximum (1023)
            0x95, 0x01,        //   Report Count (1)
            0x75, 0x0A,        //   Report Size (10)
            0x81, 0x02,        //   Input (Data,Var,Abs)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x00,        //   Logical Maximum (0)
            0x75, 0x06,        //   Report Size (6) - Padding
            0x95, 0x01,        //   Report Count (1)
            0x81, 0x03,        //   Input (Const,Var,Abs)
            0x05, 0x01,        //   Usage Page (Generic Desktop Ctrls)
            0x09, 0x35,        //   Usage (Rz) - Right Trigger
            0x15, 0x00,        //   Logical Minimum (0)
            0x26, 0xFF, 0x03,  //   Logical Maximum (1023)
            0x95, 0x01,        //   Report Count (1)
            0x75, 0x0A,        //   Report Size (10)
            0x81, 0x02,        //   Input (Data,Var,Abs)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x00,        //   Logical Maximum (0)
            0x75, 0x06,        //   Report Size (6) - Padding
            0x95, 0x01,        //   Report Count (1)
            0x81, 0x03,        //   Input (Const,Var,Abs)
            0x05, 0x01,        //   Usage Page (Generic Desktop Ctrls)
            0x09, 0x39,        //   Usage (Hat Switch / D-Pad)
            0x15, 0x01,        //   Logical Minimum (1)
            0x25, 0x08,        //   Logical Maximum (8)
            0x35, 0x00,        //   Physical Minimum (0)
            0x46, 0x3B, 0x01,  //   Physical Maximum (315)
            0x66, 0x14, 0x00,  //   Unit (English Rotation, Degree)
            0x75, 0x04,        //   Report Size (4)
            0x95, 0x01,        //   Report Count (1)
            0x81, 0x42,        //   Input (Data,Var,Abs,Null)
            0x75, 0x04,        //   Report Size (4) - Padding
            0x95, 0x01,        //   Report Count (1)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x00,        //   Logical Maximum (0)
            0x35, 0x00,        //   Physical Minimum (0)
            0x45, 0x00,        //   Physical Maximum (0)
            0x65, 0x00,        //   Unit (None)
            0x81, 0x03,        //   Input (Const,Var,Abs)
            0x05, 0x09,        //   Usage Page (Button)
            0x19, 0x01,        //   Usage Minimum (Button 1)
            0x29, 0x0A,        //   Usage Maximum (Button 10)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            0x75, 0x01,        //   Report Size (1)
            0x95, 0x0A,        //   Report Count (10)
            0x81, 0x02,        //   Input (Data,Var,Abs)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x00,        //   Logical Maximum (0)
            0x75, 0x06,        //   Report Size (6) - Padding
            0x95, 0x01,        //   Report Count (1)
            0x81, 0x03,        //   Input (Const,Var,Abs)

            // Guide Button Collection (Report ID 2)
            0x05, 0x01,        //   Usage Page (Generic Desktop Ctrls)
            0x09, 0x80,        //   Usage (System Control)
            0x85, 0x02,        //   Report ID (2)
            0xA1, 0x00,        //   Collection (Physical)
            0x09, 0x85,        //     Usage (System Main Menu / Guide Button)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x01,        //     Logical Maximum (1)
            0x95, 0x01,        //     Report Count (1)
            0x75, 0x01,        //     Report Size (1)
            0x81, 0x02,        //     Input (Data,Var,Abs)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x00,        //     Logical Maximum (0)
            0x75, 0x07,        //     Report Size (7) - Padding
            0x95, 0x01,        //     Report Count (1)
            0x81, 0x03,        //     Input (Const,Var,Abs)
            0xC0,              //   End Collection

            // Rumble / Force Feedback Output (Report ID 3)
            0x05, 0x0F,        //   Usage Page (Physical Interface Device)
            0x09, 0x21,        //   Usage (Set Effect Report)
            0x85, 0x03,        //   Report ID (3)
            0xA1, 0x02,        //   Collection (Logical)
            0x09, 0x97,        //     Usage (DC Enable Actuators)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x01,        //     Logical Maximum (1)
            0x75, 0x04,        //     Report Size (4)
            0x95, 0x01,        //     Report Count (1)
            0x91, 0x02,        //     Output (Data,Var,Abs)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x00,        //     Logical Maximum (0)
            0x75, 0x04,        //     Report Size (4) - Padding
            0x95, 0x01,        //     Report Count (1)
            0x91, 0x03,        //     Output (Const,Var,Abs)
            0x09, 0x70,        //     Usage (Magnitude)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x64,        //     Logical Maximum (100)
            0x75, 0x08,        //     Report Size (8)
            0x95, 0x04,        //     Report Count (4)
            0x91, 0x02,        //     Output (Data,Var,Abs)
            0x09, 0x50,        //     Usage (Duration)
            0x66, 0x01, 0x10,  //     Unit (Seconds * 10^-2 = 10 ms)
            0x55, 0x0E,        //     Unit Exponent (-2)
            0x15, 0x00,        //     Logical Minimum (0)
            0x26, 0xFF, 0x00,  //     Logical Maximum (255)
            0x75, 0x08,        //     Report Size (8)
            0x95, 0x01,        //     Report Count (1)
            0x91, 0x02,        //     Output (Data,Var,Abs)
            0x09, 0xA7,        //     Usage (Start Delay)
            0x15, 0x00,        //     Logical Minimum (0)
            0x26, 0xFF, 0x00,  //     Logical Maximum (255)
            0x75, 0x08,        //     Report Size (8)
            0x95, 0x01,        //     Report Count (1)
            0x91, 0x02,        //     Output (Data,Var,Abs)
            0x65, 0x00,        //     Unit (None)
            0x55, 0x00,        //     Unit Exponent (0)
            0x09, 0x7C,        //     Usage (Loop Count)
            0x15, 0x00,        //     Logical Minimum (0)
            0x26, 0xFF, 0x00,  //     Logical Maximum (255)
            0x75, 0x08,        //     Report Size (8)
            0x95, 0x01,        //     Report Count (1)
            0x91, 0x02,        //     Output (Data,Var,Abs)
            0xC0,              //   End Collection

            // Battery Report (Report ID 4)
            0x85, 0x04,        //   Report ID (4)
            0x05, 0x06,        //   Usage Page (Generic Device Controls)
            0x09, 0x20,        //   Usage (Battery Strength)
            0x15, 0x00,        //   Logical Minimum (0)
            0x26, 0xFF, 0x00,  //   Logical Maximum (255)
            0x75, 0x08,        //   Report Size (8)
            0x95, 0x01,        //   Report Count (1)
            0x81, 0x02,        //   Input (Data,Var,Abs)
            0xC0               // End Collection
        ).map { it.toByte() }.toByteArray()
    }
}
