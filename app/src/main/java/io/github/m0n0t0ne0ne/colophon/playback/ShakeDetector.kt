package io.github.m0n0t0ne0ne.colophon.playback

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Calls [onShake] on a deliberate shake, with a cooldown so one shake fires once. */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {
    private var lastShakeAt = 0L

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gForce > SHAKE_G) {
            val now = System.currentTimeMillis()
            if (now - lastShakeAt > COOLDOWN_MS) {
                lastShakeAt = now
                onShake()
            }
        }
    }

    private companion object {
        const val SHAKE_G = 2.3f
        const val COOLDOWN_MS = 1000L
    }
}
