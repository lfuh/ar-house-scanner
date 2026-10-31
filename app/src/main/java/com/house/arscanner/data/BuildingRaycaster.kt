package com.house.arscanner.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

data class RaycastBuildingMatch(
    val houseNumber: String,
    val street: String?,
    val city: String?,
    val postcode: String?,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Int,
    val angleOffsetDeg: Float,
    val nearbyAddressesOnBlock: List<String>
)

/**
 * Combines OpenStreetMap 2D building-footprint / parcel coordinates (`addr:housenumber`)
 * with the camera's compass ray (`headingDegrees`) and ML Kit Camera OCR (`cameraOcrNumber`)
 * to lock onto the exact house in the camera crosshairs rather than relying on a blind GPS offset.
 */
object BuildingRaycaster {

    fun raycastToBuildingFootprints(
        userLat: Double,
        userLon: Double,
        headingDegrees: Float,
        cameraOcrNumber: String?
    ): RaycastBuildingMatch? {
        try {
            val query = """
                [out:json][timeout:3];
                (
                  node["addr:housenumber"](around:70,$userLat,$userLon);
                  way["addr:housenumber"](around:70,$userLat,$userLon);
                );
                out center;
            """.trimIndent()
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://overpass-api.de/api/interpreter?data=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "ARHouseScannerAndroid/1.0")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val elements = root.optJSONArray("elements") ?: return null
            if (elements.length() == 0) return null

            data class CandidateParcel(
                val houseNumber: String,
                val street: String?,
                val city: String?,
                val postcode: String?,
                val lat: Double,
                val lon: Double,
                val distMeters: Double,
                val bearingDeg: Float,
                val angleDiffDeg: Float
            )

            val candidates = mutableListOf<CandidateParcel>()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val tags = el.optJSONObject("tags") ?: continue
                val houseNum = tags.optString("addr:housenumber").trim()
                if (houseNum.isEmpty()) continue

                val street = tags.optString("addr:street").trim().takeIf { it.isNotEmpty() }
                val city = tags.optString("addr:city").trim().takeIf { it.isNotEmpty() }
                val postcode = tags.optString("addr:postcode").trim().takeIf { it.isNotEmpty() }

                val cLat = if (el.has("lat")) {
                    el.optDouble("lat", Double.NaN)
                } else {
                    el.optJSONObject("center")?.optDouble("lat", Double.NaN) ?: Double.NaN
                }
                val cLon = if (el.has("lon")) {
                    el.optDouble("lon", Double.NaN)
                } else {
                    el.optJSONObject("center")?.optDouble("lon", Double.NaN) ?: Double.NaN
                }
                if (cLat.isNaN() || cLon.isNaN()) continue

                val dist = distanceMetersBetween(userLat, userLon, cLat, cLon)
                if (dist < 3.0 || dist > 75.0) continue

                val bearing = bearingDegreesBetween(userLat, userLon, cLat, cLon)
                val angleDiff = angularDifferenceDeg(headingDegrees, bearing)

                candidates.add(
                    CandidateParcel(
                        houseNumber = houseNum,
                        street = street,
                        city = city,
                        postcode = postcode,
                        lat = cLat,
                        lon = cLon,
                        distMeters = dist,
                        bearingDeg = bearing,
                        angleDiffDeg = angleDiff
                    )
                )
            }

            if (candidates.isEmpty()) return null

            // 1. If Camera ML Kit OCR read a house number, check if it matches any parcel around us
            val ocrMatch = if (!cameraOcrNumber.isNullOrBlank()) {
                candidates.firstOrNull { it.houseNumber.equals(cameraOcrNumber, ignoreCase = true) }
            } else {
                null
            }

            // 2. Otherwise pick the building closest to the camera's forward ray (within ±42° cone)
            val forwardCandidates = candidates
                .filter { it.angleDiffDeg <= 42f }
                .sortedWith(compareBy { it.angleDiffDeg * 1.6 + it.distMeters * 0.25 })

            val best = ocrMatch
                ?: forwardCandidates.firstOrNull()
                ?: candidates.minByOrNull { it.angleDiffDeg * 1.5 + it.distMeters * 0.3 }
                ?: return null

            val dominantStreet = best.street
                ?: candidates.firstOrNull { it.street != null }?.street

            val nearbyLabels = candidates
                .sortedBy { it.angleDiffDeg }
                .mapNotNull { c ->
                    val st = c.street ?: dominantStreet ?: return@mapNotNull null
                    "${c.houseNumber} $st"
                }
                .distinct()
                .take(5)

            return RaycastBuildingMatch(
                houseNumber = best.houseNumber,
                street = dominantStreet,
                city = best.city,
                postcode = best.postcode,
                lat = best.lat,
                lon = best.lon,
                distanceMeters = best.distMeters.roundToInt(),
                angleOffsetDeg = best.angleDiffDeg,
                nearbyAddressesOnBlock = nearbyLabels
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Generates adjacent house numbers along the same street (e.g. 3484, 3488, 3492, 3496 Cowper Ct)
     * when Overpass doesn't have individual building nodes mapped on that block, so the user
     * can still switch to the exact house next door in 1 tap.
     */
    fun buildNeighborHouseCandidates(
        streetAddress: String,
        city: String,
        overpassList: List<String>
    ): List<String> {
        if (overpassList.size >= 3) {
            return overpassList.map { "$it, $city" }
        }
        val numStr = streetAddress.substringBefore(" ").trim()
        val streetName = streetAddress.substringAfter(" ", "").trim()
        val baseNum = numStr.toIntOrNull()
        if (baseNum == null || streetName.isEmpty()) return emptyList()

        val offsets = listOf(-8, -4, 0, 4, 8)
        return offsets
            .map { baseNum + it }
            .filter { it > 0 }
            .map { "$it $streetName, $city" }
    }

    private fun distanceMetersBetween(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat1))
        val dy = (lat2 - lat1) * mPerDegLat
        val dx = (lon2 - lon1) * mPerDegLon
        return hypot(dx, dy)
    }

    private fun bearingDegreesBetween(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val theta = Math.toDegrees(atan2(y, x)).toFloat()
        return (theta + 360f) % 360f
    }

    private fun angularDifferenceDeg(a: Float, b: Float): Float {
        val diff = ((a - b + 540f) % 360f) - 180f
        return abs(diff)
    }
}
