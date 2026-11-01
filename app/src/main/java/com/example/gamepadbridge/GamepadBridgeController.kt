package com.example.gamepadbridge

import android.bluetooth.BluetoothDevice

/**
 * Controller abstraction to decouple Android 9+ BluetoothHidDevice APIs
 * from MainActivity, preventing classloader VerifyErrors on Android 7.0 (API 24).
 */
interface GamepadBridgeController {
    var onConnectionChanged: ((Boolean) -> Unit)?
    fun start()
    fun stop()
    fun bondedDevices(): List<Pair<String, BluetoothDevice>>
    fun connectTo(device: BluetoothDevice)
    fun notifyStateChanged()
}
