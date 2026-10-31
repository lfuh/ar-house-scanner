package com.house.arscanner.camera

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.hypot

data class CameraVisionResult(
    val detectedHouseNumber: String? = null,
    val detectedStreetHint: String? = null,
    val allDetectedNumbers: List<String> = emptyList()
)

/**
 * Analyzes the live camera frame captured at the moment of target lock using
 * on-device Google ML Kit Text Recognition (`com.google.mlkit:text-recognition`).
 *
 * If a house plaque, mailbox number, or curb address number (e.g. "3488", "3492", "20275")
 * is visible in the camera viewfinder, it extracts that exact number so we can pinpoint
 * the exact house even when GPS is off by 5–15 meters.
 */
object CameraHouseRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun analyzeCurrentCameraFrame(): CameraVisionResult = withTimeoutOrNull(1800L) {
        val bitmap = captureOnMainThread() ?: return@withTimeoutOrNull CameraVisionResult()
        runMlKitOcr(bitmap)
    } ?: CameraVisionResult()

    private suspend fun captureOnMainThread(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cont.resume(LiveCameraFrameHolder.captureCurrentFrame())
        } else {
            Handler(Looper.getMainLooper()).post {
                if (cont.isActive) {
                    cont.resume(LiveCameraFrameHolder.captureCurrentFrame())
                }
            }
        }
    }

    private suspend fun runMlKitOcr(bitmap: Bitmap): CameraVisionResult =
        suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val centerX = bitmap.width / 2f
                val centerY = bitmap.height / 2f

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (!cont.isActive) return@addOnSuccessListener

                        data class NumberCandidate(
                            val number: String,
                            val distanceFromCenter: Float
                        )

                        val candidates = mutableListOf<NumberCandidate>()
                        var streetHint: String? = null

                        for (block in visionText.textBlocks) {
                            for (line in block.lines) {
                                val text = line.text.trim()
                                val box = line.boundingBox
                                val dist = if (box != null) {
                                    hypot(
                                        (box.centerX() - centerX).toDouble(),
                                        (box.centerY() - centerY).toDouble()
                                    ).toFloat()
                                } else {
                                    9999f
                                }

                                // Match typical standalone house numbers (2 to 5 digits, e.g. 334, 3488, 20275)
                                Regex("""\b([1-9]\d{1,4})\b""").findAll(text).forEach { m ->
                                    val numStr = m.groupValues[1]
                                    val numVal = numStr.toIntOrNull() ?: 0
                                    if (numVal in 10..99999) {
                                        candidates.add(NumberCandidate(numStr, dist))
                                    }
                                }

                                // Check if a street suffix appears in the camera frame (e.g. "Cowper Ct", "Northglen Sq")
                                if (streetHint == null &&
                                    Regex(
                                        """\b[A-Za-z]{3,15}\s+(?:St|Ave|Rd|Dr|Ct|Ln|Way|Pl|Sq|Blvd|Cir|Ter)\b""",
                                        RegexOption.IGNORE_CASE
                                    ).containsMatchIn(text)
                                ) {
                                    streetHint = text
                                }
                            }
                        }

                        // Sort candidates by proximity to the center AR reticle
                        val sorted = candidates
                            .sortedBy { it.distanceFromCenter }
                            .map { it.number }
                            .distinct()

                        cont.resume(
                            CameraVisionResult(
                                detectedHouseNumber = sorted.firstOrNull(),
                                detectedStreetHint = streetHint,
                                allDetectedNumbers = sorted.take(4)
                            )
                        )
                    }
                    .addOnFailureListener {
                        if (cont.isActive) {
                            cont.resume(CameraVisionResult())
                        }
                    }
            } catch (_: Exception) {
                if (cont.isActive) {
                    cont.resume(CameraVisionResult())
                }
            }
        }
}
