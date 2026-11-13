package com.scylla.tool.phonemic.pc

/**
 * A system webcam we can push frames into. Like the audio side (VB-Cable / BlackHole), the
 * driver itself is someone else's already-signed one - here OBS Studio's virtual camera.
 */
interface VirtualCamera {
    val label: String

    /** Starts the camera at a fixed size; returns an error message, or null on success. */
    fun open(width: Int, height: Int, fps: Int): String?

    /** One NV12 frame of exactly width*height*3/2 bytes. Throws if the camera went away. */
    fun write(nv12: ByteArray)

    fun close()
}

object VirtualCameras {
    const val BUSY = "Virtual camera busy. Stop OBS's own virtual camera"

    /** The virtual camera for this OS, or null when OBS (or our macOS helper) isn't available. */
    fun create(): VirtualCamera? {
        val os = System.getProperty("os.name").lowercase()
        return try {
            when {
                os.contains("win") -> if (ObsWindowsCamera.isInstalled()) ObsWindowsCamera() else null
                os.contains("mac") -> ObsMacCamera.locateHelper()?.let { ObsMacCamera(it) }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
