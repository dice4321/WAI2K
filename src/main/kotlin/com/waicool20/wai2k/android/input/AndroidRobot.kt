/*
 * GPLv3 License
 *
 *  Copyright (c) WAI2K by waicool20
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.waicool20.wai2k.android.input

import com.waicool20.wai2k.android.AndroidScreen
import com.waicool20.wai2k.android.enums.EventType
import com.waicool20.wai2k.android.enums.InputEvent
import com.waicool20.wai2k.android.enums.Key
import org.sikuli.basics.AnimatorOutQuarticEase
import org.sikuli.basics.AnimatorTimeBased
import org.sikuli.basics.Settings
import org.sikuli.script.*
import java.awt.Color
import java.awt.Rectangle
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * A class representing a [org.sikuli.script.IRobot] that can be used to control a [AndroidScreen],
 * not recommended for normal use as this is not thread safe, any actions generated will be
 * unpredictable behaviour. It is recommended to use [AndroidKeyboard] and [AndroidTouchInterface] as
 * a thread safe wrapper to control this robot.
 *
 * @property screen [AndroidScreen] that is bound to this robot.
 * @constructor Main constructor
 * @param screen [AndroidScreen] to use this robot on.
 */
class AndroidRobot(val screen: AndroidScreen) : IRobot {
    data class Touch(
            var cursorX: Int = 0,
            var cursorY: Int = 0,
            var isTouching: AtomicBoolean = AtomicBoolean(false)
    )

    private var autoDelay = 100
    private val heldKeys = mutableSetOf<Int>()

    private val _touches: MutableList<Touch>

    /**
     * Retrieves a copy of the current touch status
     */
    val touches: List<Touch> get() = Collections.unmodifiableList(_touches.toList())

    init {
        // Determine max amount of touch slots the device can handle
        val maxSlots = screen.device.input.specs[InputEvent.ABS_MT_SLOT]?.maxValue ?: 1
        _touches = List(maxSlots) { Touch() }.toMutableList()

        // Keep track of the x y coordinates by monitoring the input event bus
        thread(isDaemon = true) {
            screen.device.execute("getevent ${screen.device.input.devFile}").bufferedReader().forEachLine {
                val (_, sCode, sValue) = it.trim().split(Regex("\\s+"))
                val eventCode = sCode.toLong(16)
                val value = sValue.toLong(16)
                when (eventCode) {
                    InputEvent.ABS_MT_POSITION_X.code -> {
                        _touches[0].cursorX = valueToCoord(value, InputEvent.ABS_MT_POSITION_X)
                    }
                    InputEvent.ABS_MT_POSITION_Y.code -> {
                        _touches[0].cursorY = valueToCoord(value, InputEvent.ABS_MT_POSITION_Y)
                    }
                }
            }
        }
    }

    //<editor-fold desc="Mouse stuff">

    /**
     * Releases a touch
     *
     * @param buttons Mouse buttons to release. Ignored
     * @return The currently held buttons. Always 0
     */
    override fun mouseUp(buttons: Int): Int {
        lowLevelTouchActions { touchUp(0) }
        return 0
    }

    /**
     * Starts a touch action
     *
     * @param buttons Mouse buttons to press. Ignored
     */
    override fun mouseDown(buttons: Int) {
        lowLevelTouchActions { touchDown(0) }
    }

    /**
     * Moves the cursor to the coordinates instantly.
     *
     * @param x x coordinate to move the cursor to.
     * @param y y coordinate to move the cursor to.
     */
    override fun mouseMove(x: Int, y: Int) {
        lowLevelTouchActions { touchMove(0, x, y) }
    }

    /**
     * Spins the mouse wheel. Unsupported
     *
     * @param wheelAmt Amount of steps to spin the wheel, can be negative to change direction.
     */
    override fun mouseWheel(wheelAmt: Int) {
        throw UnsupportedOperationException()
    }

    /**
     * Moves the cursor to the destination in a smooth fashion.
     *
     * @param dest Location to move the cursor to.
     */
    override fun smoothMove(dest: Location) =
            smoothMove(Location(_touches[0].cursorX, _touches[0].cursorY), dest, (Settings.MoveMouseDelay * 1000).toLong())

    /**
     * Moves the cursor from a source to the destination in a smooth fashion.
     *
     * @param src Location to move the cursor from
     * @param dest Location to move the cursor to.
     * @param ms Length of time to complete this action within in milliseconds.
     */
    override fun smoothMove(src: Location, dest: Location, ms: Long) {
        smoothTouchMove(listOf(TouchMove(0, src, dest)), ms)
    }

    /**
     * Resets the cursor state. Mouse is moved to (0, 0) and all buttons are released.
     */
    override fun mouseReset() {
        mouseMove(0, 0)
        mouseUp(0)
    }

    //</editor-fold>

    //<editor-fold desc="Keyboard stuff">

    /**
     * Releases all keys.
     */
    override fun keyUp() = heldKeys.forEach { keyUp(it) }

    /**
     * Releases key with the given code.
     *
     * @param code Key to release.
     */
    override fun keyUp(code: Int) {
        sendKeyEvent(Key.fromSikuliKeyCode(code), InputEvent.KEY_UP)
        syncEvent()
    }

    /**
     * Releases keys specified in a string
     *
     * @param keys Keys to release.
     */
    override fun keyUp(keys: String) = keys.toCharArray().forEach {
        sendKeyEvent(Key.findByChar(it), InputEvent.KEY_UP)
        syncEvent()
    }

    /**
     * Presses key with the given code.
     *
     * @param code Key to press.
     */
    override fun keyDown(code: Int) {
        sendKeyEvent(Key.fromSikuliKeyCode(code), InputEvent.KEY_DOWN)
        syncEvent()
    }

    /**
     * Presses keys specified in a string.
     *
     * @param keys Keys to press.
     */
    override fun keyDown(keys: String) = keys.toCharArray().forEach {
        sendKeyEvent(Key.findByChar(it), InputEvent.KEY_DOWN)
        syncEvent()
    }

    /**
     * Types a given character.
     *
     * @param char Character to type.
     * @param mode Mode to type the character with.
     */
    override fun typeChar(char: Char, mode: IRobot.KeyMode) {
        when (mode) {
            IRobot.KeyMode.PRESS_ONLY -> {
                if (char.isUpperCase() || Key.requiresShift(char)) pressModifiers(KeyModifier.SHIFT)
                keyDown("$char")
            }
            IRobot.KeyMode.PRESS_RELEASE -> {
                if (char.isUpperCase() || Key.requiresShift(char)) pressModifiers(KeyModifier.SHIFT)
                keyDown("$char")
                keyUp("$char")
                if (char.isUpperCase() || Key.requiresShift(char)) releaseModifiers(KeyModifier.SHIFT)
            }
            IRobot.KeyMode.RELEASE_ONLY -> {
                keyUp("$char")
                if (char.isUpperCase() || Key.requiresShift(char)) releaseModifiers(KeyModifier.SHIFT)
            }
        }
    }

    /**
     * Types a given key.
     *
     * @param key Key to type.
     */
    override fun typeKey(key: Int) {
        keyDown(key)
        keyUp(key)
    }

    /**
     * Presses key modifiers.
     *
     * @param modifiers The key modifiers to press.
     */
    override fun pressModifiers(modifiers: Int) {
        if (modifiers and KeyModifier.SHIFT != 0) sendKeyEvent(Key.KEY_LEFTSHIFT, InputEvent.KEY_DOWN)
        if (modifiers and KeyModifier.CTRL != 0) sendKeyEvent(Key.KEY_LEFTCTRL, InputEvent.KEY_DOWN)
        if (modifiers and KeyModifier.ALT != 0) sendKeyEvent(Key.KEY_LEFTALT, InputEvent.KEY_DOWN)
        if (modifiers and KeyModifier.META != 0 || modifiers and KeyModifier.WIN != 0) {
            sendKeyEvent(Key.KEY_LEFTMETA, InputEvent.KEY_DOWN)
        }
    }

    /**
     * Releases key modifiers.
     *
     * @param modifiers The key modifiers to release.
     */
    override fun releaseModifiers(modifiers: Int) {
        if (modifiers and KeyModifier.SHIFT != 0) sendKeyEvent(Key.KEY_LEFTSHIFT, InputEvent.KEY_UP)
        if (modifiers and KeyModifier.CTRL != 0) sendKeyEvent(Key.KEY_LEFTCTRL, InputEvent.KEY_UP)
        if (modifiers and KeyModifier.ALT != 0) sendKeyEvent(Key.KEY_LEFTALT, InputEvent.KEY_UP)
        if (modifiers and KeyModifier.META != 0 || modifiers and KeyModifier.WIN != 0) {
            sendKeyEvent(Key.KEY_LEFTMETA, InputEvent.KEY_UP)
        }
    }

    //</editor-fold>

    //<editor-fold desc="Misc">

    /**
     * Sets the auto delay for this robot.
     */
    override fun setAutoDelay(ms: Int) {
        autoDelay = ms
    }

    /**
     * Returns whether or not the robot is controlling a remote screen. Always true.
     */
    override fun isRemote() = true

    /**
     * Sleeps the current thread for a duration
     *
     * @param ms Time to sleep in milliseconds.
     */
    override fun delay(ms: Int) = TimeUnit.MILLISECONDS.sleep(ms.toLong())

    //</editor-fold>

    //<editor-fold desc="Screen Stuff">

    /**
     * Takes a screenshot of the screen bound to this robot
     *
     * @param screenRect Sub-region to capture.
     * @return the captured image.
     */
    override fun captureScreen(screenRect: Rectangle): ScreenImage = screen.capture(screenRect)

    /**
     * Gets the bound screen instance.
     */
    override fun getScreen(): IScreen = screen

    /**
     * Gets the color of the pixel at coordinates.
     *
     * @param x x coordinate of the pixel.
     * @param y y coordinate of the pixel.
     */
    override fun getColorAt(x: Int, y: Int): Color = Color((screen.lastScreenImage
            ?: screen.capture()).image.getRGB(x, y))

    //</editor-fold>

    //<editor-fold desc="Stuff that does Nothing">

    /**
     * Does nothing, ignore.
     */
    override fun cleanup() = Unit

    /**
     * Does nothing, ignore.
     */
    override fun clickStarts() = Unit

    /**
     * Does nothing, ignore.
     */
    override fun clickEnds() = Unit

    /**
     * Does nothing, ignore.
     */
    override fun typeStarts() = Unit

    /**
     * Does nothing, ignore.
     */
    override fun typeEnds() = Unit

    /**
     * Does nothing, ignore.
     */
    override fun waitForIdle() = Unit

    //</editor-fold>

    //<editor-fold desc="Touch Stuff">

    inner class TouchActions {
        fun touchUp(slot: Int) {
            if (_touches[slot].isTouching.compareAndSet(true, false)) {
                sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_SLOT, slot.toLong())
                sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_PRESSURE, 0)
                sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_TRACKING_ID, 0xffffffff)
            }
        }

        fun touchDown(slot: Int) {
            if (_touches[slot].isTouching.compareAndSet(false, true)) {
                sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_SLOT, slot.toLong())
                sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_TRACKING_ID, slot.toLong())
                sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_TOUCH_MAJOR, 127)
                sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_PRESSURE, 127)
                sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_POSITION_X, coordToValue(_touches[slot].cursorX, InputEvent.ABS_MT_POSITION_X))
                sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_POSITION_Y, coordToValue(_touches[slot].cursorY, InputEvent.ABS_MT_POSITION_Y))
            }
        }

        fun touchMove(slot: Int, x: Int, y: Int) {
            val xChanged = _touches[slot].cursorX != x
            val yChanged = _touches[slot].cursorY != y
            _touches[slot].cursorX = if (xChanged) x else _touches[slot].cursorX
            _touches[slot].cursorY = if (yChanged) y else _touches[slot].cursorY
            if (_touches[slot].isTouching.get()) {
                if (xChanged || yChanged) sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_SLOT, slot.toLong())
                if (xChanged) sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_POSITION_X, coordToValue(x, InputEvent.ABS_MT_POSITION_X))
                if (yChanged) sendEvent(EventType.EV_ABS, InputEvent.ABS_MT_POSITION_Y, coordToValue(y, InputEvent.ABS_MT_POSITION_Y))
            }
        }
    }

    data class TouchMove(val slot: Int, val src: Location? = null, val dest: Location)

    fun smoothTouchMove(touches: List<TouchMove>, ms: Long = (Settings.MoveMouseDelay * 1000).toLong()) {
        if (ms < 1) {
            touches.forEach {
                lowLevelTouchActions { touchMove(it.slot, it.dest.x, it.dest.y) }
            }
            return
        }

        fun animator(x: Int, y: Int) =
                AnimatorTimeBased(AnimatorOutQuarticEase(x.toFloat(), y.toFloat(), ms))

        val animator = touches.map {
            it to (animator(it.src?.x ?: _touches[it.slot].cursorX, it.dest.x) to
                    animator(it.src?.y ?: _touches[it.slot].cursorY, it.dest.y))
        }.toMap()

        while (animator.values.any {  it.first.running() }) {
            lowLevelTouchActions {
                animator.forEach { touchMove, animators ->
                    val x = animators.first.step()
                    val y = animators.second.step()
                    touchMove(touchMove.slot, x.roundToInt(), y.roundToInt())
                    delay(10)
                }
            }
        }
    }

    fun lowLevelTouchActions(action: TouchActions.() -> Unit) {
        TouchActions().action()
        syncEvent()
    }

    //</editor-fold>

    private fun sendEvent(type: EventType, code: InputEvent, value: Long) {
        screen.device.execute("sendevent ${screen.device.input.devFile} ${type.code} ${code.code} $value").read()
    }

    private fun sendKeyEvent(key: Key, event: InputEvent) {
        screen.device.execute("sendevent ${screen.device.input.devFile} ${EventType.EV_KEY.code} ${key.code} ${event.code}").read()
    }

    private fun syncEvent() = sendEvent(EventType.EV_SYN, InputEvent.SYN_REPORT, 0)

    private fun valueToCoord(value: Long, code: InputEvent): Int {
        return when (code) {
            InputEvent.ABS_MT_POSITION_X -> {
                val max = screen.device.input.specs[code]?.maxValue ?: 1
                ((value / max.toDouble()) * screen.device.properties.displayWidth).roundToInt()
            }
            InputEvent.ABS_MT_POSITION_Y -> {
                val max = screen.device.input.specs[code]?.maxValue ?: 1
                ((value / max.toDouble()) * screen.device.properties.displayHeight).roundToInt()
            }
            else -> error("Unsupported code")
        }
    }

    private fun coordToValue(coord: Int, code: InputEvent): Long {
        return when (code) {
            InputEvent.ABS_MT_POSITION_X -> {
                val max = screen.device.input.specs[code]?.maxValue ?: 1
                ((coord / screen.device.properties.displayWidth.toDouble()) * max).roundToInt()
            }
            InputEvent.ABS_MT_POSITION_Y -> {
                val max = screen.device.input.specs[code]?.maxValue ?: 1
                ((coord / screen.device.properties.displayHeight.toDouble()) * max).roundToInt()
            }
            else -> error("Unsupported code")
        }.toLong()
    }
}
