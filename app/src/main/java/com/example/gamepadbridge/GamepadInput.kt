package com.example.gamepadbridge

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Reads gamepad events and updates GamepadState.
 */
object GamepadInput {

    // >>> ADJUST THESE VALUES TO MATCH YOUR CONTROLLER AXIS MAP <<<
    // On many Xbox/PlayStation controllers the right stick is Z/RZ;
    // on others it is RX/RY. If your right stick does not respond, swap here.
    private const val RIGHT_STICK_X = MotionEvent.AXIS_Z
    private const val RIGHT_STICK_Y = MotionEvent.AXIS_RZ

    /** Minimum deadzone added to flat value declared by the controller. */
    private const val MIN_DEADZONE = 0.05f

    private fun hasSource(source: Int, flag: Int) = (source and flag) == flag

    private fun isGamepad(source: Int) =
        hasSource(source, InputDevice.SOURCE_GAMEPAD) ||
            hasSource(source, InputDevice.SOURCE_JOYSTICK) ||
            hasSource(source, InputDevice.SOURCE_DPAD)

    private fun getButtonBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> Btn.A
        KeyEvent.KEYCODE_BUTTON_B -> Btn.B
        KeyEvent.KEYCODE_BUTTON_X -> Btn.X
        KeyEvent.KEYCODE_BUTTON_Y -> Btn.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> Btn.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> Btn.R1
        KeyEvent.KEYCODE_BUTTON_SELECT -> Btn.SELECT
        KeyEvent.KEYCODE_BUTTON_START -> Btn.START
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Btn.THUMBL
        KeyEvent.KEYCODE_BUTTON_THUMBR -> Btn.THUMBR
        KeyEvent.KEYCODE_BUTTON_MODE -> Btn.MODE
        KeyEvent.KEYCODE_BUTTON_L2 -> Btn.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> Btn.R2
        else -> -1
    }

    /** Returns true if the event originated from a gamepad and was consumed. */
    fun handleKey(event: KeyEvent, state: GamepadState): Boolean {
        if (!isGamepad(event.source)) return false
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false

        val pressed = action == KeyEvent.ACTION_DOWN
        val bit = getButtonBit(event.keyCode)

        if (bit != -1) {
            state.setButton(bit, pressed)
            return true
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { state.setDpadKey(0, pressed); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { state.setDpadKey(1, pressed); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { state.setDpadKey(2, pressed); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { state.setDpadKey(3, pressed); true }
            else -> false
        }
    }

    fun handleMotion(event: MotionEvent, state: GamepadState): Boolean {
        if (!hasSource(event.source, InputDevice.SOURCE_JOYSTICK)) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        val source = event.source

        // Axis value with deadzone applied.
        fun axis(id: Int): Float {
            val value = event.getAxisValue(id)
            if (abs(value) <= MIN_DEADZONE) return 0f
            val flat = device.getMotionRange(id, source)?.flat ?: 0f
            return if (flat > MIN_DEADZONE && abs(value) <= flat) 0f else value
        }

        // Some controllers use LTRIGGER/RTRIGGER, others use BRAKE/GAS.
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

        state.setAxes(
            lx = axis(MotionEvent.AXIS_X),
            ly = axis(MotionEvent.AXIS_Y),
            rx = axis(RIGHT_STICK_X),
            ry = axis(RIGHT_STICK_Y),
            lt = lt.coerceIn(0f, 1f),
            rt = rt.coerceIn(0f, 1f)
        )
        state.setHatAxes(
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        )
        return true
    }
}
