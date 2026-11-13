package com.scylla.tool.phonemic.pc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.WinReg

/**
 * Feeds OBS Studio's Windows virtual camera ("OBS Virtual Camera" DirectShow device) by
 * playing the role OBS itself plays: owning the named shared-memory frame queue its
 * filter reads from. OBS doesn't need to be running, only installed (its installer
 * registers the device). Reimplemented from the layout OBS documents in
 * shared/obs-shared-memory-queue; no OBS code is used.
 *
 * Layout (little-endian): header (80 bytes, padded to 96), then 3 frame slots, each
 * [timestamp u64][24 unused bytes][NV12 frame], each slot start aligned to 32 bytes.
 */
class ObsWindowsCamera : VirtualCamera {
    companion object {
        private const val CLSID_KEY = "CLSID\\{A3FCE0F5-3493-419F-958A-ABA1250EC20B}"
        private const val MAPPING_NAME = "OBSVirtualCamVideo"

        private const val FILE_MAP_READ = 0x0004
        private const val FILE_MAP_ALL_ACCESS = 0x000F001F
        private const val PAGE_READWRITE = 0x04
        private val INVALID_HANDLE_VALUE: Pointer = Pointer.createConstant(-1L)

        private const val HEADER_SIZE = 80
        private const val FRAME_HEADER_SIZE = 32
        private const val OFF_WRITE_IDX = 0L
        private const val OFF_READ_IDX = 4L
        private const val OFF_STATE = 8L
        private const val OFF_OFFSETS = 12L
        private const val OFF_TYPE = 24L
        private const val OFF_CX = 28L
        private const val OFF_CY = 32L
        private const val OFF_INTERVAL = 40L

        private const val STATE_STARTING = 1
        private const val STATE_READY = 2
        private const val STATE_STOPPING = 3

        /** OBS's installer registers the virtual camera's COM class here. */
        fun isInstalled(): Boolean = try {
            Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, CLSID_KEY)
        } catch (_: Throwable) {
            false
        }

        private fun align32(size: Long): Long = (size + 31) and 31L.inv()

        private val kernel32: Kernel32Lite by lazy { Native.load("kernel32", Kernel32Lite::class.java) }
    }

    @Suppress("FunctionName")
    private interface Kernel32Lite : Library {
        fun OpenFileMappingW(desiredAccess: Int, inheritHandle: Boolean, name: WString): Pointer?
        fun CreateFileMappingW(file: Pointer, attributes: Pointer?, protect: Int, sizeHigh: Int, sizeLow: Int, name: WString): Pointer?
        fun MapViewOfFile(mapping: Pointer, desiredAccess: Int, offsetHigh: Int, offsetLow: Int, bytes: BaseTSD.SIZE_T): Pointer?
        fun UnmapViewOfFile(address: Pointer): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    override val label = "OBS Virtual Camera"

    private var handle: Pointer? = null
    private var view: Pointer? = null
    private val slotOffsets = LongArray(3)
    private var frameSize = 0
    private var writeIndex = 0

    override fun open(width: Int, height: Int, fps: Int): String? {
        close()
        val name = WString(MAPPING_NAME)

        // Someone else already owns the queue: OBS's own virtual camera, or our previous one
        // that a reader (Discord, a browser...) hasn't let go of yet.
        kernel32.OpenFileMappingW(FILE_MAP_READ, false, name)?.let { existing ->
            val state = kernel32.MapViewOfFile(existing, FILE_MAP_READ, 0, 0, BaseTSD.SIZE_T(0))?.let { v ->
                val s = v.getInt(OFF_STATE)
                kernel32.UnmapViewOfFile(v)
                s
            }
            kernel32.CloseHandle(existing)
            return if (state == STATE_STOPPING) "Virtual camera is restarting" else VirtualCameras.BUSY
        }

        frameSize = width * height * 3 / 2
        var size = align32(HEADER_SIZE.toLong())
        for (i in 0 until 3) {
            slotOffsets[i] = size
            size = align32(size + frameSize + FRAME_HEADER_SIZE)
        }

        val mapping = kernel32.CreateFileMappingW(INVALID_HANDLE_VALUE, null, PAGE_READWRITE, 0, size.toInt(), name)
            ?: return "Could not create the virtual camera buffer (error ${Native.getLastError()})"
        val mapped = kernel32.MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, BaseTSD.SIZE_T(0))
        if (mapped == null) {
            kernel32.CloseHandle(mapping)
            return "Could not map the virtual camera buffer (error ${Native.getLastError()})"
        }
        handle = mapping
        view = mapped
        writeIndex = 0

        mapped.setInt(OFF_WRITE_IDX, 0)
        mapped.setInt(OFF_READ_IDX, 0)
        mapped.setInt(OFF_STATE, STATE_STARTING)
        for (i in 0 until 3) mapped.setInt(OFF_OFFSETS + 4L * i, slotOffsets[i].toInt())
        mapped.setInt(OFF_TYPE, 0) // video
        mapped.setInt(OFF_CX, width)
        mapped.setInt(OFF_CY, height)
        mapped.setLong(OFF_INTERVAL, 10_000_000L / fps) // frame interval in 100 ns units
        return null
    }

    override fun write(nv12: ByteArray) {
        val v = view ?: throw IllegalStateException("Virtual camera is not open")
        // Same order OBS uses: claim the slot, fill it, then publish it via read_idx.
        val inc = ++writeIndex
        v.setInt(OFF_WRITE_IDX, inc)
        val slot = slotOffsets[((inc.toLong() and 0xFFFFFFFFL) % 3).toInt()]
        v.setLong(slot, System.nanoTime())
        v.write(slot + FRAME_HEADER_SIZE, nv12, 0, frameSize)
        v.setInt(OFF_READ_IDX, inc)
        v.setInt(OFF_STATE, STATE_READY)
    }

    override fun close() {
        view?.let {
            it.setInt(OFF_STATE, STATE_STOPPING) // readers fall back to OBS's placeholder and let go
            kernel32.UnmapViewOfFile(it)
        }
        handle?.let { kernel32.CloseHandle(it) }
        view = null
        handle = null
    }
}
