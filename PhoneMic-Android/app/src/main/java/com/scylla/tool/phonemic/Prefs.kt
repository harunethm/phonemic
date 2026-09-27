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
