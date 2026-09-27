package com.scylla.tool.phonemic.pc

import javax.swing.SwingUtilities

fun main() {
    SwingUtilities.invokeLater {
        ReceiverUi().isVisible = true
    }
}
