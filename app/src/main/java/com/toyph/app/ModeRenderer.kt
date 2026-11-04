package com.toyph.app.modes

import com.toyph.app.util.GridConstants.GRID_SIZE
import kotlin.random.Random

/** Draws the clock and the sound visualizer frames for the 13x13 matrix. */
object ModeRenderer {

    private const val CLOCK_BRIGHTNESS = 2000
    private const val EQ_BRIGHTNESS = 3000

    // 3x5 digits, '#' = pixel on
    private val DIGITS = arrayOf(
        "###" + "#.#" + "#.#" + "#.#" + "###",
        ".#." + "##." + ".#." + ".#." + "###",
        "###" + "..#" + "###" + "#.." + "###",
        "###" + "..#" + "###" + "..#" + "###",
        "#.#" + "#.#" + "###" + "..#" + "..#",
        "###" + "#.." + "###" + "..#" + "###",
        "###" + "#.." + "###" + "#.#" + "###",
        "###" + "..#" + "..#" + "..#" + "..#",
        "###" + "#.#" + "###" + "#.#" + "###",
        "###" + "#.#" + "###" + "..#" + "###"
    )

    /** Hours on the top row, minutes below (e.g. 14 / 07). */
    fun clockFrame(hour: Int, minute: Int): IntArray {
        val frame = IntArray(GRID_SIZE * GRID_SIZE)
        drawNumber(frame, hour, top = 1)
        drawNumber(frame, minute, top = 7)
        return frame
    }

    private fun drawNumber(frame: IntArray, value: Int, top: Int) {
        val left = 3 // 3 + 1 + 3 = 7 wide, centered in 13
        drawDigit(frame, value / 10, left, top)
        drawDigit(frame, value % 10, left + 4, top)
    }

    private fun drawDigit(frame: IntArray, digit: Int, left: Int, top: Int) {
        val pattern = DIGITS[digit]
        for (r in 0 until 5) {
            for (c in 0 until 3) {
                if (pattern[r * 3 + c] == '#') {
                    frame[(top + r) * GRID_SIZE + left + c] = CLOCK_BRIGHTNESS
                }
            }
        }
    }

    /** Simple animated equalizer: 7 bars that bounce while music plays. */
    class Equalizer {
        private val bars = 7
        private val levels = FloatArray(bars) { 1f }
        private val targets = FloatArray(bars) { 1f }
        private var tick = 0

        fun nextFrame(): IntArray {
            if (tick++ % 3 == 0) {
                for (i in 0 until bars) targets[i] = 1f + Random.nextFloat() * (GRID_SIZE - 1)
            }
            val frame = IntArray(GRID_SIZE * GRID_SIZE)
            for (i in 0 until bars) {
                levels[i] += (targets[i] - levels[i]) * 0.5f
                val height = levels[i].toInt().coerceIn(1, GRID_SIZE)
                val col = i * 2
                for (h in 0 until height) {
                    frame[(GRID_SIZE - 1 - h) * GRID_SIZE + col] = EQ_BRIGHTNESS
                }
            }
            return frame
        }
    }
}
