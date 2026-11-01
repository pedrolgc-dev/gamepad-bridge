package com.example.gamepadbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat

// "NewApi" suppressed because HidGamepad is only created on Android 9+ (see onCreate).
@SuppressLint("NewApi")
class MainActivity : AppCompatActivity() {

    private val state = GamepadState()

    // Null on Android < 9 (API 28), where Bluetooth HID Device does not exist.
    private var hid: GamepadBridgeController? = null

    private lateinit var txtStatus: TextView
    private lateinit var txtController: TextView
    private lateinit var statusDot: View
    private lateinit var gamepadView: GamepadView

    // Android 12+: requests Bluetooth permissions before starting HID.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                applyBluetoothDeviceName()
                hid?.start()
            } else {
                setStatus("Bluetooth permissions denied.")
            }
        }

    // Hot-plug: updates controller name and releases all buttons if disconnected.
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshController()
        override fun onInputDeviceChanged(deviceId: Int) = refreshController()
        override fun onInputDeviceRemoved(deviceId: Int) {
            state.reset()
            refreshController()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()
        applyDarkSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        txtStatus = findViewById(R.id.txtStatus)
        txtController = findViewById(R.id.txtController)
        statusDot = findViewById(R.id.statusDot)
        gamepadView = findViewById(R.id.gamepadView)

        // Redraws the gamepad layout and triggers transmission whenever state changes.
        gamepadView.gamepad = state
        state.onChange = {
            gamepadView.invalidate()
            hid?.notifyStateChanged()
        }

        findViewById<Button>(R.id.btnDiscoverable).setOnClickListener { makeDiscoverable() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener { chooseHost() }

        try {
            ContextCompat.getSystemService(this, InputManager::class.java)?.registerInputDeviceListener(deviceListener, null)
        } catch (_: Exception) {}

        refreshController()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hid = HidGamepad(this, state) { msg -> runOnUiThread { setStatus(msg) } }.also {
                it.onConnectionChanged = { connected ->
                    statusDot.setBackgroundResource(
                        if (connected) R.drawable.gp_dot_on else R.drawable.gp_dot_off
                    )
                }
            }
            startBluetooth()
        } else {
            setStatus("Bluetooth transmission requires Android 9 (API 28) or higher. Controller is read locally, but cannot be forwarded via Bluetooth.")
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyBluetoothDeviceName() {
        if (!hasBluetoothPermissions()) return
        try {
            val adapter = ContextCompat.getSystemService(this, BluetoothManager::class.java)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
            if (adapter?.isEnabled == true && adapter.name != BLUETOOTH_CONTROLLER_NAME) {
                adapter.name = BLUETOOTH_CONTROLLER_NAME
            }
        } catch (_: SecurityException) {
        }
    }

    private fun startBluetooth() {
        if (hasBluetoothPermissions()) {
            applyBluetoothDeviceName()
            hid?.start()
        } else {
            requestBluetoothPermissions()
        }
    }

    // Capture events here (before reaching views) so D-pad and Action buttons
    // don't change focus on screen controls.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return try {
            GamepadInput.handleKey(event, state) || super.dispatchKeyEvent(event)
        } catch (_: Exception) {
            super.dispatchKeyEvent(event)
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        return try {
            GamepadInput.handleMotion(ev, state) || super.dispatchGenericMotionEvent(ev)
        } catch (_: Exception) {
            super.dispatchGenericMotionEvent(ev)
        }
    }

    override fun onResume() {
        super.onResume()
        applyBluetoothDeviceName()
    }

    override fun onPause() {
        // Stop receiving events in background: prevents "stuck" buttons.
        state.reset()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            ContextCompat.getSystemService(this, InputManager::class.java)?.unregisterInputDeviceListener(deviceListener)
        } catch (_: Exception) {}
        hid?.stop()
        super.onDestroy()
    }

    private fun setStatus(text: String) {
        txtStatus.text = text
    }

    /** Displays the name of the first controller/gamepad detected by Android. */
    private fun refreshController() {
        try {
            var name: String? = null
            val ids = InputDevice.getDeviceIds()
            for (i in ids.indices) {
                val dev = InputDevice.getDevice(ids[i]) ?: continue
                val sources = dev.sources
                if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                ) {
                    name = dev.name
                    break
                }
            }
            txtController.text =
                if (name != null) getString(R.string.gp_controller_found, name)
                else getString(R.string.gp_controller_none)
        } catch (_: Exception) {
            txtController.text = getString(R.string.gp_controller_none)
        }
    }

    /** Dark system bars to match app background. */
    @Suppress("DEPRECATION")
    private fun applyDarkSystemBars() {
        val bg = 0xFF000000.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun makeDiscoverable() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        applyBluetoothDeviceName()
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            startActivity(intent)
        } catch (_: SecurityException) {
            setStatus("Bluetooth permission required.")
        } catch (_: Exception) {
            setStatus("Error enabling Bluetooth discoverability.")
        }
    }

    private fun chooseHost() {
        val h = hid
        if (h == null) {
            setStatus("Bluetooth HID is unavailable on this Android version.")
            return
        }
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        val devices = h.bondedDevices()
        if (devices.isEmpty()) {
            setStatus("No paired devices found. Pair from your PC first.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Connect to…")
            .setItems(devices.map { it.first }.toTypedArray()) { _, index ->
                try {
                    h.connectTo(devices[index].second)
                } catch (_: SecurityException) {
                    setStatus("Bluetooth permission required.")
                }
            }
            .show()
    }

    companion object {
        const val BLUETOOTH_CONTROLLER_NAME = "Xbox Wireless Controller"
    }
}
