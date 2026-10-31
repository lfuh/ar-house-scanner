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
 * Tracks live hardware GPS coordinates, compass heading (azimuth 0..360°), and phone camera
 * motion stability. Keeps hardware GPS coordinates strictly separate from indoor preset
 * test locations so tapping "Scan Now" always uses your true physical GPS coordinates.
 */
class StabilizationAndLocationTracker(
    private val context: Context,
    private val stabilizationDurationMs: Long = 1700L,
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

    // Hardware GPS coordinates (never overwritten by test presets!)
    var realGpsLat: Double = 37.4184
        private set
    var realGpsLon: Double = -122.1162
        private set
    var hasRealGpsFix: Boolean = false
        private set

    // Currently displayed coordinates (either realGps or temporary preset)
    var currentLat: Double = 37.4184
        private set
    var currentLon: Double = -122.1162
        private set
    private var isViewingPresetLocation: Boolean = false

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
    private var hasLocationPermissionGranted = false

    private val motionThreshold = 0.85f

    @SuppressLint("MissingPermission")
    fun start(hasLocationPermission: Boolean) {
        hasLocationPermissionGranted = hasLocationPermission
        if (!isLockedOnTarget) {
            steadyStartTimestampMs = System.currentTimeMillis()
        }

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
            refreshHardwareGpsFromProviders()
            try {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                for (provider in providers) {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.requestLocationUpdates(provider, 1500L, 1f, this)
                    }
                }
            } catch (_: SecurityException) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshHardwareGpsFromProviders() {
        if (!hasLocationPermissionGranted) return
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var bestLocation: Location? = null
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider) ?: continue
                    if (bestLocation == null || loc.time > bestLocation.time) {
                        bestLocation = loc
                    }
                }
            }
            if (bestLocation != null) {
                realGpsLat = bestLocation.latitude
                realGpsLon = bestLocation.longitude
                hasRealGpsFix = true
                if (!isViewingPresetLocation) {
                    currentLat = realGpsLat
                    currentLon = realGpsLon
                }
            }
        } catch (_: SecurityException) {
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
     * Triggered when the user taps "Scan Now", "Scan Next House", or cycles the target distance.
     * Always restores the phone's true hardware GPS coordinates (clearing any test preset).
     */
    fun triggerRealGpsScan(immediate: Boolean = true) {
        isViewingPresetLocation = false
        refreshHardwareGpsFromProviders()
        currentLat = realGpsLat
        currentLon = realGpsLon

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

    /**
     * Triggered only when the user explicitly taps one of the indoor "Test Locations" chips.
     * Does NOT overwrite [realGpsLat] or [realGpsLon].
     */
    fun triggerPresetScan(presetLat: Double, presetLon: Double) {
        isViewingPresetLocation = true
        currentLat = presetLat
        currentLon = presetLon
        isLockedOnTarget = true
        lockedHeadingDeg = currentHeadingDeg
        onStateUpdate(
            currentLat,
            currentLon,
            false,
            currentHeadingDeg,
            1.0f,
            true,
            true
        )
        onStabilizedLock(presetLat, presetLon, currentHeadingDeg)
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
                val delta = ((azimuthDeg - currentHeadingDeg + 540f) % 360f) - 180f
                currentHeadingDeg = (currentHeadingDeg + 0.15f * delta + 360f) % 360f
            }
        }

        val isSteady = lastLinearAccelMagnitude < motionThreshold

        if (isLockedOnTarget) {
            // Stay locked on the current property until the user explicitly taps "Scan Now"
            onStateUpdate(
                currentLat,
                currentLon,
                hasRealGpsFix && !isViewingPresetLocation,
                currentHeadingDeg,
                1.0f,
                true,
                true
            )
            return
        }

        if (!isSteady) {
            steadyStartTimestampMs = now
            onStateUpdate(
                currentLat,
                currentLon,
                hasRealGpsFix && !isViewingPresetLocation,
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
                    hasRealGpsFix && !isViewingPresetLocation,
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
                    hasRealGpsFix && !isViewingPresetLocation,
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
        realGpsLat = location.latitude
        realGpsLon = location.longitude
        hasRealGpsFix = true
        if (!isViewingPresetLocation) {
            currentLat = realGpsLat
            currentLon = realGpsLon
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
