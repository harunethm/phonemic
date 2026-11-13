package com.scylla.tool.phonemic.pc

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Feeds OBS Studio's macOS camera extension (OBS 30+, macOS 13+) through the bundled
 * `phonemic-vcam` helper (native/macos/phonemic-vcam.mm). CoreMediaIO needs Objective-C,
 * so the JVM side just pipes raw NV12 frames into the helper's stdin.
 *
 * Helper protocol: `phonemic-vcam <width> <height> <fps>`, prints `READY` once streaming,
 * exit code 2 = OBS camera not found / not set up, 3 = couldn't start (busy).
 */
class ObsMacCamera(private val helper: File) : VirtualCamera {
    companion object {
        private const val HELPER_NAME = "phonemic-vcam"
        private const val READY_TIMEOUT_MS = 3000L

        /** The helper next to our jars (packaged app) or from PHONEMIC_VCAM_HELPER (`./gradlew run`); null without OBS. */
        fun locateHelper(): File? {
            if (!File("/Applications/OBS.app").exists()) return null
            System.getenv("PHONEMIC_VCAM_HELPER")?.let { path -> File(path).takeIf { it.isFile }?.let { return it } }
            return try {
                val jar = File(ObsMacCamera::class.java.protectionDomain.codeSource.location.toURI())
                File(jar.parentFile, HELPER_NAME).takeIf { it.isFile }
            } catch (_: Exception) {
                null
            }
        }
    }

    override val label = "OBS Virtual Camera"

    private var process: Process? = null
    private var stdin: OutputStream? = null

    override fun open(width: Int, height: Int, fps: Int): String? {
        close()
        helper.setExecutable(true)
        val started = try {
            ProcessBuilder(helper.path, width.toString(), height.toString(), fps.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        } catch (e: Exception) {
            return "Could not start the virtual camera helper: ${e.message}"
        }

        val ready = CompletableFuture<Boolean>()
        Thread({
            try {
                started.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.trim() == "READY") ready.complete(true)
                    }
                }
            } catch (_: Exception) {
            }
            ready.complete(false)
        }, "vcam-helper-out").apply { isDaemon = true }.start()

        val ok = try { ready.get(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
        if (!ok) {
            val code = if (started.waitFor(500, TimeUnit.MILLISECONDS)) started.exitValue() else {
                started.destroy()
                -1
            }
            return when (code) {
                2 -> "OBS virtual camera not set up. Start and stop it once in OBS"
                3 -> VirtualCameras.BUSY
                else -> "Virtual camera helper failed (exit $code)"
            }
        }
        process = started
        stdin = BufferedOutputStream(started.outputStream, 1 shl 20)
        return null
    }

    override fun write(nv12: ByteArray) {
        val out = stdin ?: throw IllegalStateException("Virtual camera is not open")
        out.write(nv12)
        out.flush()
    }

    override fun close() {
        try { stdin?.close() } catch (_: Exception) {} // EOF tells the helper to stop its stream
        stdin = null
        process?.let {
            if (!it.waitFor(1, TimeUnit.SECONDS)) it.destroy()
        }
        process = null
    }
}
