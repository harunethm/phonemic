package com.scylla.tool.phonemic.pc

import java.util.prefs.Preferences

/** Persists the last-used receiver settings between launches. */
object PcPrefs {
    private val prefs = Preferences.userNodeForPackage(PcPrefs::class.java)
    private const val KEY_PORT = "port"
    private const val KEY_MIXER_NAME = "mixer_name"
    private const val KEY_BOOST = "boost"
    private const val DEFAULT_PORT = 5005
    private const val DEFAULT_BOOST = 100 // 1.00x, matches ReceiverUi's boostSlider scale

    fun getPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)
    fun setPort(port: Int) {
        prefs.putInt(KEY_PORT, port)
    }

    fun getMixerName(): String? = prefs.get(KEY_MIXER_NAME, null)
    fun setMixerName(name: String) {
        prefs.put(KEY_MIXER_NAME, name)
    }

    fun getBoost(): Int = prefs.getInt(KEY_BOOST, DEFAULT_BOOST)
    fun setBoost(boost: Int) {
        prefs.putInt(KEY_BOOST, boost)
    }

    /** Horizontal flip applied to the phone camera before preview + virtual webcam. */
    fun getCameraMirror(): Boolean = prefs.getBoolean(KEY_CAMERA_MIRROR, false)
    fun setCameraMirror(mirror: Boolean) {
        prefs.putBoolean(KEY_CAMERA_MIRROR, mirror)
    }

    /** Extra clockwise quarter turns on top of the phone's own orientation, 0..3. */
    fun getCameraRotate(): Int = prefs.getInt(KEY_CAMERA_ROTATE, 0).mod(4)
    fun setCameraRotate(quarterTurns: Int) {
        prefs.putInt(KEY_CAMERA_ROTATE, quarterTurns.mod(4))
    }

    private const val KEY_CAMERA_MIRROR = "camera_mirror"
    private const val KEY_CAMERA_ROTATE = "camera_rotate"
}
