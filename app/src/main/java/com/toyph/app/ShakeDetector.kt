package com.toyph.app.modes

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Calls [onShake] when the phone is shaken (two strong jolts within 600 ms). */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastJoltAt = 0L
    private var lastShakeAt = 0L

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gForce = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        ) / SensorManager.GRAVITY_EARTH
        if (gForce < JOLT_G) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastShakeAt < COOLDOWN_MS) return
        if (now - lastJoltAt in 80..600) {
            lastShakeAt = now
            lastJoltAt = 0L
            onShake()
        } else {
            lastJoltAt = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val JOLT_G = 2.5f
        private const val COOLDOWN_MS = 1500L
    }
}
