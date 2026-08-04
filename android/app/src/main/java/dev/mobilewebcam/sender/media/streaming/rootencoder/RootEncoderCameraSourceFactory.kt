package dev.mobilewebcam.sender.media.streaming.rootencoder

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.pedro.encoder.input.sources.video.Camera2Source

internal data class RootEncoderCameraDescriptor(
    val selectionId: String?,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val label: String,
)

class RootEncoderCameraSourceFactory(
    private val context: Context,
) {
    private val cameraManager: CameraManager by lazy {
        checkNotNull(context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager) {
            "Camera service is unavailable"
        }
    }

    fun createCameraSource(): Camera2Source = Camera2Source(context)

    internal fun availableCameraDescriptors(): List<RootEncoderCameraDescriptor> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()

        return runCatching {
            val cameras = cameraManager.cameraIdList.mapNotNull(::cameraMetadata)
            val rearCamera = cameras.firstOrNull { it.isRear && it.physicalCameraIds.isNotEmpty() }
                ?: return@runCatching emptyList()
            val frontCamera = cameras.firstOrNull { it.isFront }

            buildList {
                add(
                    RootEncoderCameraDescriptor(
                        selectionId = null,
                        logicalCameraId = rearCamera.cameraId,
                        physicalCameraId = null,
                        label = "$AUTOMATIC_LENS_LABEL (Rear ID ${rearCamera.cameraId})",
                    ),
                )
                rearCamera.physicalCameraIds.sorted().forEach { physicalCameraId ->
                    add(
                        RootEncoderCameraDescriptor(
                            selectionId = physicalCameraId,
                            logicalCameraId = rearCamera.cameraId,
                            physicalCameraId = physicalCameraId,
                            label = "$PHYSICAL_LENS_LABEL (ID $physicalCameraId)",
                        ),
                    )
                }
                frontCamera?.let {
                    add(
                        RootEncoderCameraDescriptor(
                            selectionId = it.cameraId,
                            logicalCameraId = it.cameraId,
                            physicalCameraId = null,
                            label = "$FRONT_LENS_LABEL (ID ${it.cameraId})",
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun cameraMetadata(cameraId: String): CameraMetadata? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null

        return runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: return@runCatching null
            CameraMetadata(
                cameraId = cameraId,
                isRear = lensFacing == CameraCharacteristics.LENS_FACING_BACK,
                isFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
                physicalCameraIds = characteristics.physicalCameraIds,
            )
        }.getOrNull()
    }

    private data class CameraMetadata(
        val cameraId: String,
        val isRear: Boolean,
        val isFront: Boolean,
        val physicalCameraIds: Set<String>,
    )

    private companion object {
        const val AUTOMATIC_LENS_LABEL = "Auto"
        const val FRONT_LENS_LABEL = "Front"
        const val PHYSICAL_LENS_LABEL = "Physical"
    }
}
