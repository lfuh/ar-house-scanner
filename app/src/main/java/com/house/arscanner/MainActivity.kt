package com.house.arscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.house.arscanner.camera.LiveCameraPreview
import com.house.arscanner.data.PropertyValuationEngine
import com.house.arscanner.model.PropertyValuation
import com.house.arscanner.sensor.StabilizationAndLocationTracker
import com.house.arscanner.ui.ArHouseTargetOverlay
import com.house.arscanner.ui.BottomPropertyInsightSheet
import com.house.arscanner.ui.TopTelemetryHudBar
import com.house.arscanner.ui.bindPresetLocationCallback
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)
    private var hasLocationPermission by mutableStateOf(false)

    // Live sensor & stabilization state
    private var currentLat by mutableDoubleStateOf(37.4184)
    private var currentLon by mutableDoubleStateOf(-122.1162)
    private var hasRealGps by mutableStateOf(false)
    private var currentHeadingDeg by mutableFloatStateOf(45f)
    private var stabilityProgress by mutableFloatStateOf(0f)
    private var isSteady by mutableStateOf(true)
    private var isLocked by mutableStateOf(false)
    private var isLoadingLookup by mutableStateOf(false)

    // Distance from camera to target house across the street (cycles 15m -> 25m -> 40m)
    private var targetDistanceMeters by mutableIntStateOf(24)

    // Resolved property valuation & bottom sheet state
    private var currentValuation by mutableStateOf<PropertyValuation?>(null)
    private var isDetailsExpanded by mutableStateOf(false)

    private lateinit var tracker: StabilizationAndLocationTracker
    private var activeLookupJob: Job? = null

    private var pendingPresetLabel: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissions()
        tracker.start(hasLocationPermission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()

        tracker = StabilizationAndLocationTracker(
            context = this,
            stabilizationDurationMs = 1700L,
            onStateUpdate = { lat, lon, realGps, heading, progress, steady, locked ->
                currentLat = lat
                currentLon = lon
                hasRealGps = realGps
                currentHeadingDeg = heading
                stabilityProgress = progress
                isSteady = steady
                if (!locked && isLocked) {
                    // User panned camera away to another house; collapse expanded details while re-stabilizing
                    isDetailsExpanded = false
                }
                isLocked = locked
            },
            onStabilizedLock = { lat, lon, heading ->
                val preset = pendingPresetLabel
                pendingPresetLabel = null
                performHouseLookup(lat, lon, heading, targetDistanceMeters, preset)
            }
        )

        bindPresetLocationCallback { presetLabel, presetLat, presetLon ->
            pendingPresetLabel = presetLabel
            tracker.triggerManualOrPresetScan(
                overrideLat = presetLat,
                overrideLon = presetLon,
                immediate = true
            )
        }

        if (!hasCameraPermission || !hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFF00E676),
                    background = Color(0xFF060D17),
                    surface = Color(0xFF101C2E)
                )
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF060D17)
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // 1. Live Back-Camera Viewfinder
                        LiveCameraPreview(
                            hasCameraPermission = hasCameraPermission,
                            modifier = Modifier.fillMaxSize()
                        )

                        // 2. Center AR Stabilization Reticle + Floating House Price Pin
                        ArHouseTargetOverlay(
                            stabilityProgress = stabilityProgress,
                            isSteady = isSteady,
                            isLocked = isLocked,
                            isLoadingLookup = isLoadingLookup,
                            property = currentValuation,
                            onOpenDetailsSheet = {
                                isDetailsExpanded = !isDetailsExpanded
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // 3. Top GPS, Compass Heading & Preset Switcher HUD
                        TopTelemetryHudBar(
                            lat = currentLat,
                            lon = currentLon,
                            hasRealGps = hasRealGps,
                            headingDeg = currentHeadingDeg,
                            targetDistanceMeters = targetDistanceMeters,
                            isLocked = isLocked,
                            isSteady = isSteady,
                            stabilityProgress = stabilityProgress,
                            onCycleDistance = {
                                targetDistanceMeters = when (targetDistanceMeters) {
                                    15 -> 24
                                    24 -> 40
                                    else -> 15
                                }
                                tracker.triggerManualOrPresetScan(immediate = true)
                            },
                            onRescanClick = {
                                isDetailsExpanded = false
                                currentValuation = null
                                tracker.triggerManualOrPresetScan(immediate = true)
                            },
                            modifier = Modifier.align(Alignment.TopCenter)
                        )

                        // 4. Permission Banner if Camera or GPS permission was skipped
                        if (!hasCameraPermission || !hasLocationPermission) {
                            Surface(
                                color = Color(0xE61E1424),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 96.dp, start = 16.dp, end = 16.dp)
                                    .fillMaxWidth()
                                    .border(1.dp, Color(0xFFFFCA28), RoundedCornerShape(12.dp))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Camera & Location Permissions Recommended",
                                        color = Color(0xFFFFCA28),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Grant Camera and Fine Location permissions so the AR viewfinder can see the house and use your live GPS + Compass.",
                                        color = Color.White,
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Button(
                                        onClick = {
                                            permissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.CAMERA,
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                )
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFFFCA28),
                                            contentColor = Color.Black
                                        )
                                    ) {
                                        Text("Grant Camera & GPS", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // 5. Bottom Property Valuation & School/Rent/History Sheet
                        BottomPropertyInsightSheet(
                            property = currentValuation,
                            isExpanded = isDetailsExpanded,
                            onToggleExpand = { isDetailsExpanded = !isDetailsExpanded },
                            onLockNewHouse = {
                                isDetailsExpanded = false
                                currentValuation = null
                                tracker.triggerManualOrPresetScan(immediate = false)
                            },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }
    }

    private fun performHouseLookup(
        lat: Double,
        lon: Double,
        headingDeg: Float,
        distanceMeters: Int,
        presetAddressLabel: String? = null
    ) {
        activeLookupJob?.cancel()
        activeLookupJob = lifecycleScope.launch {
            isLoadingLookup = true
            val result = PropertyValuationEngine.lookupHouseAtCameraTarget(
                context = this@MainActivity,
                userLat = lat,
                userLon = lon,
                headingDegrees = headingDeg,
                distanceMeters = distanceMeters,
                presetAddressLabel = presetAddressLabel
            )
            currentValuation = result
            isLoadingLookup = false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        tracker.start(hasLocationPermission)
    }

    override fun onPause() {
        tracker.stop()
        super.onPause()
    }

    private fun refreshPermissions() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
