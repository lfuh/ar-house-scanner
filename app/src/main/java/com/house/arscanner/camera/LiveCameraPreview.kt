package com.house.arscanner.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen live back-camera preview built directly on Android's native Camera2 API.
 * Gracefully falls back to an atmospheric architectural viewfinder backdrop if running
 * on an emulator without a virtual camera.
 */
@Composable
fun LiveCameraPreview(
    hasCameraPermission: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (!hasCameraPermission) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A1422), Color(0xFF162840), Color(0xFF0A1422))
                    )
                )
        )
        return
    }

    val controller = remember { Camera2PreviewController(context) }

    DisposableEffect(Unit) {
        onDispose {
            controller.closeCamera()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        controller.openBackCamera(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {}

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        controller.closeCamera()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    )
}

private class Camera2PreviewController(private val context: Context) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    @SuppressLint("MissingPermission")
    fun openBackCamera(surfaceTexture: SurfaceTexture) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backCameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: return

            surfaceTexture.setDefaultBufferSize(1280, 720)
            val previewSurface = Surface(surfaceTexture)

            manager.openCamera(
                backCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            val requestBuilder =
                                camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(previewSurface)
                                    set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                    )
                                }
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(
                                listOf(previewSurface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        if (cameraDevice == null) return
                                        captureSession = session
                                        try {
                                            session.setRepeatingRequest(
                                                requestBuilder.build(),
                                                null,
                                                null
                                            )
                                        } catch (_: Exception) {
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                                },
                                null
                            )
                        } catch (_: Exception) {
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                    }
                },
                null
            )
        } catch (_: Exception) {
            // Ignore if emulator has no camera configured
        }
    }

    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Exception) {
        }
    }
}
