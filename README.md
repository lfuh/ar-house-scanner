# AR House Value Scanner (`com.house.arscanner`)

An Android Augmented Reality (AR) + GPS + Compass real-estate valuation app located at `/Users/lfuh/codes/ar-house-scanner`.

## How It Works

1. **Live Camera2 Viewfinder (`LiveCameraPreview.kt`)**:
   - Displays a full-screen continuous-autofocus back-camera preview.
2. **Stabilization Lock & Compass Raycasting (`StabilizationAndLocationTracker.kt` & `PropertyValuationEngine.kt`)**:
   - Tracks accelerometer, gyroscope, magnetometer compass heading (`0°–360°`), and live GPS coordinates.
   - When the user points the phone camera at a house and holds steady for **1.7 seconds**, the stabilization reticle completes (`100%`) and locks onto the house.
   - Projects the user's GPS coordinates forward (`15m`, `24m`, or `40m`) along the phone's compass heading to reverse-geocode the exact house across the street (via Android `Geocoder` + OpenStreetMap Nominatim fallback).
   - Automatically unlocks and re-scans if the user pans the camera more than `16°` to a neighboring house.
3. **Hybrid AR Floating Callout + Expandable Property Report (`ArHouseScannerHud.kt`)**:
   - **Floating AR Price Callout Pin**: Appears directly over the house in the camera view with the resolved street address, **Estimated Market Value**, bed/bath/sqft summary, **Last Sold** price & date, **Est. Monthly Rent**, and **School Score**.
   - **Slide-Up Bottom Property Sheet**: Displays the full property breakdown, assigned Elementary/Middle/High school ratings (`X/10`), prior MLS/public sale history, property tax, mortgage estimate, HOA fees, and one-tap **Open on Zillow** / **Open on Redfin** buttons.
   - **Test Locations Bar**: Includes pre-configured test addresses (*3492 Cowper Ct, Palo Alto*, *20275 Northglen Sq, Cupertino*, *334 Santana Row, San Jose*, *1455 Market St, San Francisco*) for indoor/emulator testing.

## Key Source Files

- `app/src/main/java/com/house/arscanner/MainActivity.kt`
- `app/src/main/java/com/house/arscanner/camera/LiveCameraPreview.kt`
- `app/src/main/java/com/house/arscanner/sensor/StabilizationAndLocationTracker.kt`
- `app/src/main/java/com/house/arscanner/data/PropertyValuationEngine.kt`
- `app/src/main/java/com/house/arscanner/model/PropertyValuation.kt`
- `app/src/main/java/com/house/arscanner/ui/ArHouseScannerHud.kt`
- **Built APK**: `app/build/outputs/apk/debug/app-debug.apk`

## Build & Install Commands

```bash
# Build Debug APK
/Users/lfuh/codes/ar-house-scanner/gradlew -p /Users/lfuh/codes/ar-house-scanner assembleDebug

# Install & Launch on Connected Device
/Users/lfuh/Library/Android/sdk/platform-tools/adb install -r /Users/lfuh/codes/ar-house-scanner/app/build/outputs/apk/debug/app-debug.apk
/Users/lfuh/Library/Android/sdk/platform-tools/adb shell am start -n com.house.arscanner/.MainActivity
```
