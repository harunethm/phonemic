package com.scylla.tool.phonemic.pc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.awt.Dimension
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * "Phone Mic Camera": a real Windows 11 webcam (Media Foundation virtual camera) that apps
 * see without OBS, including the Windows Camera app and browsers. The camera's media source
 * is native/windows/phonemic-vcam.cpp, which Windows' Frame Server service loads; it pulls
 * NV12 frames from us over loopback TCP.
 *
 * Installing it takes one UAC prompt: the DLL is copied to Program Files (only admins can
 * change it there, which matters because SYSTEM services load it) and registered in HKLM.
 */
class MfVirtualCamera : VirtualCamera {
    enum class InstallState { UNSUPPORTED, NOT_INSTALLED, OUTDATED, INSTALLED }

    companion object {
        const val FRIENDLY_NAME = "Phone Mic Camera"
        const val NOT_INSTALLED = "Click Install webcam to add Phone Mic Camera"
        const val OUTDATED = "Click Update webcam to finish updating Phone Mic Camera"
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val CLSID = "{7A3E5C1B-4D2F-4B8A-9E61-0C5D2F8B7A43}"
        private const val DLL_NAME = "phonemic-vcam.dll"
        private const val FEED_PORT = 47654
        private const val FEED_MAGIC = 0x46564D50 // "PMVF" little-endian
        private const val MIN_WINDOWS_BUILD = 22000 // MFCreateVirtualCamera: Windows 11
        private const val ERROR_CANCELLED = 1223

        private val isWindows11: Boolean by lazy {
            System.getProperty("os.name").lowercase().contains("win") && try {
                Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "CurrentBuildNumber"
                ).toInt() >= MIN_WINDOWS_BUILD
            } catch (_: Throwable) {
                false
            }
        }

        /** The DLL shipped next to our jars, or PHONEMIC_VCAM_DLL when running from source. */
        private val bundledDll: File? by lazy {
            System.getenv("PHONEMIC_VCAM_DLL")?.let { path -> File(path).takeIf { it.isFile }?.let { return@lazy it } }
            try {
                val jar = File(MfVirtualCamera::class.java.protectionDomain.codeSource.location.toURI())
                File(jar.parentFile, DLL_NAME).takeIf { it.isFile }
            } catch (_: Exception) {
                null
            }
        }

        /** Each build installs under its own name, so an update never collides with a copy a camera app still has loaded. */
        private val installedName: String? by lazy {
            bundledDll?.let { dll ->
                val digest = MessageDigest.getInstance("SHA-256").digest(dll.readBytes())
                "phonemic-vcam-" + digest.take(6).joinToString("") { "%02x".format(it) } + ".dll"
            }
        }

        private val installDir: File
            get() = File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "Phone Mic")

        private fun registeredDll(): File? = try {
            File(Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, "Software\\Classes\\CLSID\\$CLSID\\InprocServer32", ""))
        } catch (_: Throwable) {
            null
        }

        fun installState(): InstallState {
            if (!isWindows11) return InstallState.UNSUPPORTED
            val expected = installedName ?: return InstallState.UNSUPPORTED
            val registered = registeredDll()?.takeIf { it.isFile } ?: return InstallState.NOT_INSTALLED
            return if (registered.name.equals(expected, ignoreCase = true)) InstallState.INSTALLED else InstallState.OUTDATED
        }

        /** Shows one UAC prompt, then copies and registers the DLL. Blocks; returns an error message or null. */
        fun install(): String? {
            val source = bundledDll ?: return "This build doesn't include the webcam component"
            val name = installedName ?: return "This build doesn't include the webcam component"
            val dir = installDir
            val target = File(dir, name)
            // Old copies go first (any still loaded by a running app just stay until next time).
            val command = "/c mkdir \"$dir\" 2>nul & del /q \"$dir\\phonemic-vcam-*.dll\" 2>nul & " +
                "copy /y \"${source.path}\" \"${target.path}\" >nul && regsvr32 /s \"${target.path}\""
            runElevated(command, "install")?.let { return it }
            return if (installState() == InstallState.INSTALLED) null else "Webcam install didn't take effect"
        }

        /** Unregisters Phone Mic Camera and deletes it from Program Files (one UAC prompt). */
        fun uninstall(): String? {
            val registered = registeredDll()
            val dir = installDir
            val unregister = if (registered != null) "regsvr32 /u /s \"${registered.path}\" & " else ""
            runElevated("/c $unregister del /q \"$dir\\phonemic-vcam-*.dll\" 2>nul & rmdir \"$dir\" 2>nul & exit 0", "removal")
                ?.let { return it }
            return if (registeredDll() == null) null else "Webcam removal didn't take effect"
        }

        /** Runs cmd.exe with [arguments] elevated (UAC prompt) and waits; returns an error or null. */
        private fun runElevated(arguments: String, what: String): String? {
            val info = ShellAPI.SHELLEXECUTEINFO().apply {
                cbSize = size()
                fMask = 0x00000040 // SEE_MASK_NOCLOSEPROCESS
                lpVerb = "runas"
                lpFile = "cmd.exe"
                lpParameters = arguments
                nShow = WinUser.SW_HIDE
            }
            if (!Shell32.INSTANCE.ShellExecuteEx(info)) {
                val error = Kernel32.INSTANCE.GetLastError()
                return if (error == ERROR_CANCELLED) "Webcam $what cancelled" else "Webcam $what failed (error $error)"
            }
            val process = info.hProcess ?: return null
            Kernel32.INSTANCE.WaitForSingleObject(process, 120_000)
            val exitCode = IntByReference()
            Kernel32.INSTANCE.GetExitCodeProcess(process, exitCode)
            Kernel32.INSTANCE.CloseHandle(process)
            return if (exitCode.value != 0) "Webcam $what failed (code ${exitCode.value})" else null
        }

        // All native calls happen on this one thread (COM is initialized per thread).
        private val nativeThread = Executors.newSingleThreadExecutor { Thread(it, "phone-mic-camera").apply { isDaemon = true } }
        private var library: VCamLibrary? = null

        private fun <T> onNativeThread(block: (VCamLibrary) -> T): T = nativeThread.submit<T> {
            val lib = library ?: Native.load(registeredDll()!!.path, VCamLibrary::class.java).also { library = it }
            block(lib)
        }.get()
    }

    @Suppress("FunctionName")
    private interface VCamLibrary : Library {
        fun PhoneMicVCamStart(friendlyName: WString): Int
        fun PhoneMicVCamStop(): Int
        fun PhoneMicVCamProbe(friendlyName: WString, frames: Int, width: IntByReference, height: IntByReference, averageLuma: IntByReference): Int
    }

    override val label = FRIENDLY_NAME
    override val fixedSize = Dimension(WIDTH, HEIGHT)
    // Stays registered while no phone video flows, so apps keep it selected (it shows a placeholder).
    override val keepOpenWhileIdle = true

    private var feed: FeedServer? = null
    private var started = false

    override fun open(width: Int, height: Int, fps: Int): String? {
        when (installState()) {
            InstallState.UNSUPPORTED -> return "Phone Mic Camera needs Windows 11"
            InstallState.NOT_INSTALLED -> return NOT_INSTALLED
            InstallState.OUTDATED -> return OUTDATED
            InstallState.INSTALLED -> {}
        }
        if (feed == null) feed = FeedServer.start() ?: return "Phone Mic Camera's local port $FEED_PORT is in use"
        val result = try {
            onNativeThread { it.PhoneMicVCamStart(WString(FRIENDLY_NAME)) }
        } catch (e: Throwable) {
            close()
            return "Could not start Phone Mic Camera: ${e.cause?.message ?: e.message}"
        }
        if (result < 0) {
            close()
            return "Could not start Phone Mic Camera (0x%08X)".format(result)
        }
        started = true
        return null
    }

    override fun write(nv12: ByteArray) {
        feed?.publish(nv12) ?: throw IllegalStateException("Phone Mic Camera is not open")
    }

    override fun close() {
        if (started) {
            try { onNativeThread { it.PhoneMicVCamStop() } } catch (_: Throwable) {}
            started = false
        }
        feed?.close()
        feed = null
    }

    /** Self-test: reads frames from the camera like any app would; returns (width, height, average luma). */
    fun probe(frames: Int): Triple<Int, Int, Int>? {
        val w = IntByReference()
        val h = IntByReference()
        val luma = IntByReference()
        val result = onNativeThread { it.PhoneMicVCamProbe(WString(FRIENDLY_NAME), frames, w, h, luma) }
        if (result < 0) {
            println("probe failed (0x%08X)".format(result))
            return null
        }
        return Triple(w.value, h.value, luma.value)
    }

    /**
     * Serves frames to the camera's media source in the Frame Server process. Loopback only.
     * Each frame: [magic][width][height][length] little-endian u32s, then NV12 bytes. Every
     * client gets only the newest frame - a slow reader skips frames instead of lagging.
     */
    private class FeedServer private constructor(private val server: ServerSocket) {
        companion object {
            fun start(): FeedServer? = try {
                FeedServer(ServerSocket(FEED_PORT, 4, InetAddress.getLoopbackAddress()))
            } catch (_: Exception) {
                null
            }
        }

        private val clients = CopyOnWriteArrayList<Client>()

        init {
            Thread({
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    clients += Client(socket)
                }
            }, "phone-mic-camera-feed").apply { isDaemon = true }.start()
        }

        fun publish(nv12: ByteArray) {
            if (clients.isEmpty()) return // no app is using the camera right now
            val frame = nv12.copyOf() // shared, never modified again
            clients.forEach { it.offer(frame) }
        }

        fun close() {
            try { server.close() } catch (_: Exception) {}
            clients.forEach { it.close() }
            clients.clear()
        }

        private inner class Client(private val socket: Socket) {
            private val lock = Object()
            private var pending: ByteArray? = null
            @Volatile private var closed = false

            init {
                socket.tcpNoDelay = true
                Thread({ sendLoop() }, "phone-mic-camera-client").apply { isDaemon = true }.start()
            }

            fun offer(frame: ByteArray) = synchronized(lock) {
                pending = frame
                lock.notifyAll()
            }

            fun close() {
                closed = true
                try { socket.close() } catch (_: Exception) {}
                synchronized(lock) { lock.notifyAll() }
            }

            private fun sendLoop() {
                val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                try {
                    val out = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)
                    while (!closed) {
                        val frame = synchronized(lock) {
                            while (pending == null && !closed) lock.wait(1000)
                            pending.also { pending = null }
                        } ?: continue
                        header.clear()
                        header.putInt(FEED_MAGIC).putInt(WIDTH).putInt(HEIGHT).putInt(frame.size)
                        out.write(header.array())
                        out.write(frame)
                        out.flush()
                    }
                } catch (_: Exception) {
                    // the camera app closed; the source reconnects when it's opened again
                } finally {
                    close()
                    clients.remove(this)
                }
            }
        }
    }
}
