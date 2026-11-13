package com.scylla.tool.phonemic.pc

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

// Loopback port doubles as the single-instance lock: bind succeeds only for the first process.
private const val SINGLE_INSTANCE_PORT = 47653

fun main(args: Array<String>) {
    // Developer checks for the camera path (see SelfTest.kt); they never touch the UI or the lock.
    when (args.firstOrNull()) {
        "--video-selftest" -> exitProcess(SelfTest.runVideoSelfTest())
        "--vcam-test" -> exitProcess(SelfTest.runVirtualCameraTest())
    }
    val lock = try {
        ServerSocket(SINGLE_INSTANCE_PORT, 1, InetAddress.getLoopbackAddress())
    } catch (_: Exception) {
        // Already running: poke it to raise its window, then exit.
        try { Socket(InetAddress.getLoopbackAddress(), SINGLE_INSTANCE_PORT).close() } catch (_: Exception) {}
        return
    }
    SwingUtilities.invokeLater {
        val ui = ReceiverUi()
        ui.isVisible = true
        thread(isDaemon = true) {
            while (true) {
                try { lock.accept().close() } catch (_: Exception) { break }
                SwingUtilities.invokeLater { ui.state = java.awt.Frame.NORMAL; ui.isVisible = true; ui.toFront() }
            }
        }
    }
}
