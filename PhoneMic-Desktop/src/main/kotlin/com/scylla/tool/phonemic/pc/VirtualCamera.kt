package com.scylla.tool.phonemic.pc

import java.awt.Dimension

/**
 * A system webcam we can push frames into: our own Windows 11 "Phone Mic Camera", or OBS
 * Studio's already-signed virtual camera (Windows 10 and apps that only list DirectShow
 * cameras, plus macOS).
 */
interface VirtualCamera {
    val label: String

    /** The size this camera always runs at, or null to use the phone's canvas size. */
    val fixedSize: Dimension? get() = null

    /** Stay open while no phone video flows (apps keep it selected) instead of closing on disconnect. */
    val keepOpenWhileIdle: Boolean get() = false

    /** Starts the camera at a fixed size; returns an error message, or null on success. */
    fun open(width: Int, height: Int, fps: Int): String?

    /** One NV12 frame of exactly width*height*3/2 bytes. Throws if the camera went away. */
    fun write(nv12: ByteArray)

    fun close()
}

object VirtualCameras {
    const val BUSY = "Virtual camera busy. Stop OBS's own virtual camera"

    /** Every virtual camera this machine can feed; the phone's video goes to all of them at once. */
    fun createAll(): List<VirtualCamera> {
        val os = System.getProperty("os.name").lowercase()
        val cameras = ArrayList<VirtualCamera>()
        try {
            if (os.contains("win")) {
                if (MfVirtualCamera.installState() != MfVirtualCamera.InstallState.UNSUPPORTED) cameras += MfVirtualCamera()
                if (ObsWindowsCamera.isInstalled()) cameras += ObsWindowsCamera()
            } else if (os.contains("mac")) {
                ObsMacCamera.locateHelper()?.let { cameras += ObsMacCamera(it) }
            }
        } catch (_: Throwable) {
            // a broken optional output must never take the preview down with it
        }
        return cameras
    }
}
