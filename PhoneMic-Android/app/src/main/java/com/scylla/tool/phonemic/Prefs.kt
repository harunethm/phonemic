package com.scylla.tool.phonemic

import android.content.Context

/** Persists the last-used PC pairing and capture preferences between launches. */
object Prefs {
    private const val FILE = "phone_mic_prefs"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_PIN = "pin"
    private const val KEY_SCENARIO = "scenario"
    private const val KEY_NOISE_REDUCTION = "noise_reduction"
    private const val KEY_MONITORING = "monitoring"
    private const val KEY_SUPPRESS_HIGH_GAIN_WARNING = "suppress_high_gain_warning"
    private const val KEY_CAMERA_ENABLED = "camera_enabled"
    private const val KEY_CAMERA_FACING = "camera_facing"
    private const val KEY_CAMERA_QUALITY = "camera_quality"
    private const val DEFAULT_PORT = 5005

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getHost(context: Context): String =
        prefs(context).getString(KEY_HOST, "") ?: ""

    fun setHost(context: Context, host: String) {
        prefs(context).edit().putString(KEY_HOST, host).apply()
    }

    fun getPort(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port).apply()
    }

    /** PIN handed out by the PC receiver's QR/numeric code, empty until paired. */
    fun getPin(context: Context): String =
        prefs(context).getString(KEY_PIN, "") ?: ""

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_PIN, pin).apply()
    }

    fun isPaired(context: Context): Boolean =
        getHost(context).isNotEmpty() && getPin(context).isNotEmpty()

    fun getScenario(context: Context): CaptureScenario =
        CaptureScenario.fromStorageKey(prefs(context).getString(KEY_SCENARIO, null))

    fun setScenario(context: Context, scenario: CaptureScenario) {
        prefs(context).edit().putString(KEY_SCENARIO, scenario.storageKey).apply()
    }

    fun isNoiseReductionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOISE_REDUCTION, true)

    fun setNoiseReductionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOISE_REDUCTION, enabled).apply()
    }

    fun isMonitoringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MONITORING, false)

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITORING, enabled).apply()
    }

    fun isHighGainWarningSuppressed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUPPRESS_HIGH_GAIN_WARNING, false)

    fun setHighGainWarningSuppressed(context: Context, suppressed: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUPPRESS_HIGH_GAIN_WARNING, suppressed).apply()
    }

    /** Stream the camera too (an add-on to the mic stream, never on its own). */
    fun isCameraEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAMERA_ENABLED, false)

    fun setCameraEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAMERA_ENABLED, enabled).apply()
    }

    fun getCameraFacing(context: Context): CameraFacing =
        CameraFacing.fromStorageKey(prefs(context).getString(KEY_CAMERA_FACING, null))

    fun setCameraFacing(context: Context, facing: CameraFacing) {
        prefs(context).edit().putString(KEY_CAMERA_FACING, facing.storageKey).apply()
    }

    fun getCameraQuality(context: Context): CameraQuality =
        CameraQuality.fromStorageKey(prefs(context).getString(KEY_CAMERA_QUALITY, null))

    fun setCameraQuality(context: Context, quality: CameraQuality) {
        prefs(context).edit().putString(KEY_CAMERA_QUALITY, quality.storageKey).apply()
    }
}

enum class CameraFacing(val storageKey: String) {
    BACK("back"),
    FRONT("front");

    companion object {
        fun fromStorageKey(key: String?): CameraFacing = entries.find { it.storageKey == key } ?: BACK
    }
}

/** Encoder presets: size is in sensor (landscape) orientation, 30 fps. */
enum class CameraQuality(val storageKey: String, val width: Int, val height: Int, val bitrate: Int) {
    HD("720p", 1280, 720, 4_000_000),
    FULL_HD("1080p", 1920, 1080, 8_000_000);

    companion object {
        fun fromStorageKey(key: String?): CameraQuality = entries.find { it.storageKey == key } ?: HD
    }
}

/** Capture presets from app-todo.md - each maps to an AudioSource + software gain in MicStreamService. */
enum class CaptureScenario(val storageKey: String) {
    SPEAKER_NEARBY("speaker_nearby"),
    DISTANT_VOICE("distant_voice"),
    MUSIC("music");

    companion object {
        fun fromStorageKey(key: String?): CaptureScenario =
            entries.find { it.storageKey == key } ?: SPEAKER_NEARBY
    }
}
