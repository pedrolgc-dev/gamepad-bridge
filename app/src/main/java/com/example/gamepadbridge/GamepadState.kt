package com.example.gamepadbridge

import kotlin.math.roundToInt

/** Index (bit) of each button in the HID report. Used by GamepadInput and GamepadView. */
object Btn {
    const val A = 0
    const val B = 1
    const val X = 2
    const val Y = 3
    const val L1 = 4
    const val R1 = 5
    const val SELECT = 6
    const val START = 7
    const val THUMBL = 8
    const val THUMBR = 9
    const val MODE = 10
    const val L2 = 11
    const val R2 = 12
}

/**
 * Current gamepad state (single source of truth).
 * Runs entirely on the main thread, so no synchronization is needed.
 *
 * Xbox Wireless Controller HID Report format (15 bytes, excluding Report ID 1):
 *  [0..1] Left Stick X (16-bit uint, 0..65535, center = 32768)
 *  [2..3] Left Stick Y (16-bit uint, 0..65535, center = 32768)
 *  [4..5] Right Stick X / Rx (16-bit uint, 0..65535, center = 32768)
 *  [6..7] Right Stick Y / Ry (16-bit uint, 0..65535, center = 32768)
 *  [8..9] Left Trigger / Z (10-bit uint 0..1023 + 6-bit padding)
 *  [10..11] Right Trigger / Rz (10-bit uint 0..1023 + 6-bit padding)
 *  [12]   Hat switch (low 4 bits: 0=neutral, 1=N, 2=NE, 3=E, 4=SE, 5=S, 6=SW, 7=W, 8=NW; 4-bit padding)
 *  [13..14] 10 buttons (A, B, X, Y, LB, RB, View, Menu, LSB, RSB + 6-bit padding)
 */
class GamepadState {

    /** Incremented whenever state changes; Bluetooth sender checks this for updates. */
    var version = 0L
        private set

    /** Invoked when state changes (used to trigger UI redraws & transmission). */
    var onChange: (() -> Unit)? = null

    private var buttons = 0
    private val dpadKeys = BooleanArray(4) // 0=up, 1=right, 2=down, 3=left
    private var hatX = 0f
    private var hatY = 0f

    // Reusable 15-byte report buffer for official Xbox Wireless Controller Report ID 1
    private val reportBuffer = ByteArray(15)
    private val guideBuffer = ByteArray(1)

    // Read-only externally: GamepadView uses these to draw the controller.
    var lx = 0f
        private set
    var ly = 0f
        private set
    var rx = 0f
        private set
    var ry = 0f
        private set
    var lt = 0f
        private set
    var rt = 0f
        private set

    fun isPressed(bit: Int): Boolean = ((buttons shr bit) and 1) == 1

    fun setButton(bit: Int, pressed: Boolean) {
        val mask = 1 shl bit
        val novo = if (pressed) buttons or mask else buttons and mask.inv()
        if (novo != buttons) {
            buttons = novo
            changed()
        }
    }

    fun setDpadKey(direction: Int, pressed: Boolean) {
        if (dpadKeys[direction] != pressed) {
            dpadKeys[direction] = pressed
            changed()
        }
    }

    fun setHatAxes(x: Float, y: Float) {
        if (x != hatX || y != hatY) {
            hatX = x
            hatY = y
            changed()
        }
    }

    fun setAxes(lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float) {
        if (lx != this.lx || ly != this.ly || rx != this.rx || ry != this.ry ||
            lt != this.lt || rt != this.rt
        ) {
            this.lx = lx; this.ly = ly
            this.rx = rx; this.ry = ry
            this.lt = lt; this.rt = rt
            changed()
        }
    }

    /** Resets all inputs (used when gamepad is disconnected or app goes to background). */
    fun reset() {
        buttons = 0
        dpadKeys.fill(false)
        hatX = 0f; hatY = 0f
        lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f
        changed()
    }

    private fun changed() {
        version++
        onChange?.invoke()
    }

    /** Combines D-pad keys and hat axes into a single 0..7 value (8 = neutral). */
    fun hat(): Int {
        val up = dpadKeys[0] || hatY < -0.5f
        val right = dpadKeys[1] || hatX > 0.5f
        val down = dpadKeys[2] || hatY > 0.5f
        val left = dpadKeys[3] || hatX < -0.5f
        return when {
            up && right -> 1
            right && down -> 3
            down && left -> 5
            left && up -> 7
            up -> 0
            right -> 2
            down -> 4
            left -> 6
            else -> 8
        }
    }

    fun toReport(): ByteArray {
        val rawLx = stickAxis(lx)
        reportBuffer[0] = (rawLx and 0xFF).toByte()
        reportBuffer[1] = ((rawLx shr 8) and 0xFF).toByte()

        val rawLy = stickAxis(ly)
        reportBuffer[2] = (rawLy and 0xFF).toByte()
        reportBuffer[3] = ((rawLy shr 8) and 0xFF).toByte()

        val rawRx = stickAxis(rx)
        reportBuffer[4] = (rawRx and 0xFF).toByte()
        reportBuffer[5] = ((rawRx shr 8) and 0xFF).toByte()

        val rawRy = stickAxis(ry)
        reportBuffer[6] = (rawRy and 0xFF).toByte()
        reportBuffer[7] = ((rawRy shr 8) and 0xFF).toByte()

        val ltVal = maxOf(lt, if (isPressed(Btn.L2)) 1f else 0f)
        val rawLt = triggerAxis(ltVal)
        reportBuffer[8] = (rawLt and 0xFF).toByte()
        reportBuffer[9] = ((rawLt shr 8) and 0x03).toByte()

        val rtVal = maxOf(rt, if (isPressed(Btn.R2)) 1f else 0f)
        val rawRt = triggerAxis(rtVal)
        reportBuffer[10] = (rawRt and 0xFF).toByte()
        reportBuffer[11] = ((rawRt shr 8) and 0x03).toByte()

        reportBuffer[12] = xboxHat(hat())

        var b0 = 0
        if (isPressed(Btn.A)) b0 = b0 or (1 shl 0)
        if (isPressed(Btn.B)) b0 = b0 or (1 shl 1)
        if (isPressed(Btn.X)) b0 = b0 or (1 shl 2)
        if (isPressed(Btn.Y)) b0 = b0 or (1 shl 3)
        if (isPressed(Btn.L1)) b0 = b0 or (1 shl 4)
        if (isPressed(Btn.R1)) b0 = b0 or (1 shl 5)
        if (isPressed(Btn.SELECT)) b0 = b0 or (1 shl 6)
        if (isPressed(Btn.START)) b0 = b0 or (1 shl 7)
        reportBuffer[13] = b0.toByte()

        var b1 = 0
        if (isPressed(Btn.THUMBL)) b1 = b1 or (1 shl 0)
        if (isPressed(Btn.THUMBR)) b1 = b1 or (1 shl 1)
        reportBuffer[14] = b1.toByte()

        return reportBuffer
    }

    /** Xbox Guide / Home button report (Report ID 2). */
    fun toGuideReport(): ByteArray {
        guideBuffer[0] = if (isPressed(Btn.MODE)) 1 else 0
        return guideBuffer
    }

    private fun stickAxis(v: Float): Int =
        ((v.coerceIn(-1f, 1f) + 1f) * 32767.5f).roundToInt().coerceIn(0, 65535)

    private fun triggerAxis(v: Float): Int =
        (v.coerceIn(0f, 1f) * 1023f).roundToInt().coerceIn(0, 1023)

    private fun xboxHat(hat: Int): Byte = when (hat) {
        0 -> 1 // Up
        1 -> 2 // Up-Right
        2 -> 3 // Right
        3 -> 4 // Down-Right
        4 -> 5 // Down
        5 -> 6 // Down-Left
        6 -> 7 // Left
        7 -> 8 // Up-Left
        else -> 0 // Neutral
    }.toByte()

    fun describe(): String = buildString {
        append("Buttons: ").appendLine(Integer.toBinaryString(buttons).padStart(16, '0'))
        append("Hat: ").appendLine(hat())
        appendLine("Left: %.2f / %.2f".format(lx, ly))
        appendLine("Right: %.2f / %.2f".format(rx, ry))
        append("Triggers: %.2f / %.2f".format(lt, rt))
    }
}
