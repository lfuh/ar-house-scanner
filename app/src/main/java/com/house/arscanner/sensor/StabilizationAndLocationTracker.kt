package com.house.arscanner.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlin.math.sqrt

/**
 * Tracks live GPS coordinates, compass heading (azimuth 0..360°), and phone camera
 * motion stability. When the user holds the phone steady pointing at a house for
 * [stabilizationDurationMs] (default 1.8 seconds), [onStabilizedLock] fires automatically.
 */
class StabilizationAndLocationTracker(
    private val context: Context,
    private val stabilizationDurationMs: Long = 1800L,
    private val onStateUpdate: (
        lat: Double,
        lon: Double,
        hasRealGps: Boolean,
        headingDeg: Float,
        stabilityProgress: Float,
        isSteady: Boolean,
        isLocked: Boolean
    ) -> Unit,
    private val onStabilizedLock: (lat: Double, lon: Double, headingDeg: Float) -> Unit
) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Default coordinates (3492 Cowper Ct, Palo Alto, CA) until live GPS fix arrives
    var currentLat: Double = 37.4184
        private set
    var currentLon: Double = -122.1162
        private set
    var hasRealGpsFix: Boolean = false
        private set
    var currentHeadingDeg: Float = 45f
        private set

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private var lastLinearAccelMagnitude = 0f
    private var steadyStartTimestampMs = 0L
    private var isLockedOnTarget = false
    private var lockedHeadingDeg = 0f

    // Thresholds: how much angular / linear motion is allowed while still counting as "steady"
    private val motionThreshold = 0.85f
    private val unlockHeadingDeltaDeg = 16f

    @SuppressLint("MissingPermission")
    fun start(hasLocationPermission: Boolean) {
        steadyStartTimestampMs = System.currentTimeMillis()
        isLockedOnTarget = false

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        if (hasLocationPermission) {
            try {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                for (provider in providers) {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.requestLocationUpdates(provider, 2000L, 2f, this)
                        val lastKnown = locationManager.getLastKnownLocation(provider)
                        if (lastKnown != null) {
                            currentLat = lastKnown.latitude
                            currentLon = lastKnown.longitude
                            hasRealGpsFix = true
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Handled gracefully if permission was revoked
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    /**
     * Forces an immediate re-scan / re-stabilization cycle (or locks immediately if [immediate] is true).
     */
    fun triggerManualOrPresetScan(
        overrideLat: Double? = null,
        overrideLon: Double? = null,
        immediate: Boolean = true
    ) {
        if (overrideLat != null && overrideLon != null) {
            currentLat = overrideLat
            currentLon = overrideLon
        }
        if (immediate) {
            isLockedOnTarget = true
            lockedHeadingDeg = currentHeadingDeg
            onStateUpdate(
                currentLat,
                currentLon,
                hasRealGpsFix,
                currentHeadingDeg,
                1.0f,
                true,
                true
            )
            onStabilizedLock(currentLat, currentLon, currentHeadingDeg)
        } else {
            isLockedOnTarget = false
            steadyStartTimestampMs = System.currentTimeMillis()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val alpha = 0.85f
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
                hasGravity = true

                // Linear acceleration (shake / movement detection)
                val lx = event.values[0] - gravity[0]
                val ly = event.values[1] - gravity[1]
                val lz = event.values[2] - gravity[2]
                lastLinearAccelMagnitude = sqrt(lx * lx + ly * ly + lz * lz)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                val alpha = 0.85f
                geomagnetic[0] = alpha * geomagnetic[0] + (1 - alpha) * event.values[0]
                geomagnetic[1] = alpha * geomagnetic[1] + (1 - alpha) * event.values[1]
                geomagnetic[2] = alpha * geomagnetic[2] + (1 - alpha) * event.values[2]
                hasGeomagnetic = true
            }

            Sensor.TYPE_GYROSCOPE -> {
                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]
                val rotRate = sqrt(wx * wx + wy * wy + wz * wz)
                if (rotRate > 0.55f) {
                    // Phone is actively panning across the street
                    lastLinearAccelMagnitude = motionThreshold + rotRate
                }
            }
        }

        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val azimuthDeg = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
                // Smooth compass heading
                val delta = ((azimuthDeg - currentHeadingDeg + 540f) % 360f) - 180f
                currentHeadingDeg = (currentHeadingDeg + 0.15f * delta + 360f) % 360f
            }
        }

        val isSteady = lastLinearAccelMagnitude < motionThreshold

        if (isLockedOnTarget) {
            // Check if the user panned the camera to look at a different house (>16° heading change or sharp shake)
            val headingDiff = kotlin.math.abs(((currentHeadingDeg - lockedHeadingDeg + 540f) % 360f) - 180f)
            if (headingDiff > unlockHeadingDeltaDeg || lastLinearAccelMagnitude > motionThreshold * 2.4f) {
                isLockedOnTarget = false
                steadyStartTimestampMs = now
            } else {
                onStateUpdate(
                    currentLat,
                    currentLon,
                    hasRealGpsFix,
                    currentHeadingDeg,
                    1.0f,
                    true,
                    true
                )
                return
            }
        }

        if (!isSteady) {
            // Reset stabilization timer when user is moving the camera
            steadyStartTimestampMs = now
            onStateUpdate(
                currentLat,
                currentLon,
                hasRealGpsFix,
                currentHeadingDeg,
                0.08f,
                false,
                false
            )
        } else {
            if (steadyStartTimestampMs == 0L) {
                steadyStartTimestampMs = now
            }
            val elapsed = now - steadyStartTimestampMs
            val progress = (elapsed.toFloat() / stabilizationDurationMs.toFloat()).coerceIn(0f, 1f)

            if (progress >= 1.0f && !isLockedOnTarget) {
                isLockedOnTarget = true
                lockedHeadingDeg = currentHeadingDeg
                onStateUpdate(
                    currentLat,
                    currentLon,
                    hasRealGpsFix,
                    currentHeadingDeg,
                    1.0f,
                    true,
                    true
                )
                onStabilizedLock(currentLat, currentLon, currentHeadingDeg)
            } else {
                onStateUpdate(
                    currentLat,
                    currentLon,
                    hasRealGpsFix,
                    currentHeadingDeg,
                    progress,
                    true,
                    false
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        currentLat = location.latitude
        currentLon = location.longitude
        hasRealGpsFix = true
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
