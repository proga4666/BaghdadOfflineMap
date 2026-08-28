package com.offlinemap.baghdad.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

class CompassSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    private var lastGravity = FloatArray(3)
    private var lastGeomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private var currentAzimuth = 0f
    private val smoothingAlpha = 0.18f // Smooth low-pass filter factor

    var onAzimuthChanged: ((Float) -> Unit)? = null

    private var lastEmitTime = 0L

    fun start() {
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            if (accelSensor != null) sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
            if (magnetSensor != null) sensorManager.registerListener(this, magnetSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationValues)
            var azimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360f
            updateAzimuth(azimuth)
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, lastGravity, 0, event.values.size)
            hasGravity = true
            checkFusedOrientation()
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, lastGeomagnetic, 0, event.values.size)
            hasGeomagnetic = true
            checkFusedOrientation()
        }
    }

    private fun checkFusedOrientation() {
        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, lastGravity, lastGeomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (azimuth < 0) azimuth += 360f
                updateAzimuth(azimuth)
            }
        }
    }

    private fun updateAzimuth(newAzimuth: Float) {
        var diff = newAzimuth - currentAzimuth
        // Handle wrap-around at 360 degrees
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360

        currentAzimuth = (currentAzimuth + diff * smoothingAlpha + 360) % 360

        val now = System.currentTimeMillis()
        if (abs(diff) > 1.8f && now - lastEmitTime >= 65L) {
            lastEmitTime = now
            onAzimuthChanged?.invoke(currentAzimuth)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
