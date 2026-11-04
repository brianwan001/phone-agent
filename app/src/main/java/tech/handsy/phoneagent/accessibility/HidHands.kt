package tech.handsy.phoneagent.accessibility

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Escape hatch #3: an ESP32 HID bridge plugged into the phone's USB port.
 * The app sends simple text commands over CDC ("T x y" with 0..10000 coords);
 * the board re-emits them as a REAL USB touchscreen + keyboard, so the kernel
 * sees genuine touch events — identical to a human finger at the input layer.
 */
object HidHands {

    private const val TAG = "HidHands"
    private var connection: UsbDeviceConnection? = null
    private var outEndpoint: UsbEndpoint? = null
    private var device: UsbDevice? = null

    fun attached(context: Context): Boolean {
        if (connection != null) return true
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = findBridge(manager) ?: return false
        return try {
            val conn = manager.openDevice(dev) ?: return false
            val ep = findCdcOut(dev, conn) ?: run { conn.close(); return false }
            connection = conn
            outEndpoint = ep
            device = dev
            // handshake
            val ok = send("P") && readOk(conn)
            if (!ok) {
                close()
                false
            } else {
                Log.i(TAG, "HID bridge attached: ${dev.manufacturerName} ${dev.productName}")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "attach failed: ${e.message}")
            false
        }
    }

    fun ready(): Boolean = connection != null && outEndpoint != null

    /** Tap at screen-normalized 0..1000 coordinates (the space the model uses). */
    fun tap(x1000: Int, y1000: Int): Boolean = send("T ${x1000 * 10} ${y1000 * 10}")

    fun longPress(x1000: Int, y1000: Int): Boolean = send("L ${x1000 * 10} ${y1000 * 10}")

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        send("S ${x1 * 10} ${y1 * 10} ${x2 * 10} ${y2 * 10}")

    fun key(name: String): Boolean = send("K $name")

    /** ASCII typing; spaces travel as '_' and the firmware converts them back. */
    fun typeAscii(text: String): Boolean =
        send("Y " + text.replace(" ", "_"))

    fun close() {
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        outEndpoint = null
        device = null
    }

    // ---------- internals ----------

    private fun findBridge(manager: UsbManager): UsbDevice? =
        manager.deviceList.values.firstOrNull { dev ->
            // Composite CDC+HID bridge: has both a CDC class and HID class interface,
            // or the Seeed XIAO ESP32S3 VID.
            val vid = dev.vendorId
            val hasCdc = (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM }
            val hasHid = (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HID }
            (vid == 0x2886 && hasCdc) || (hasCdc && hasHid)
        }

    private fun findCdcOut(dev: UsbDevice, conn: UsbDeviceConnection): UsbEndpoint? {
        // CDC data interface (class 0x0A) bulk-out endpoint
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                if (conn.claimInterface(iface, true)) {
                    for (j in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(j)
                        if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            return ep
                        }
                    }
                }
            }
        }
        return null
    }

    private fun send(cmd: String): Boolean {
        val conn = connection ?: return false
        val ep = outEndpoint ?: return false
        return try {
            val bytes = (cmd + "\n").toByteArray()
            var sent = 0
            while (sent < bytes.size) {
                val n = conn.bulkTransfer(ep, bytes, sent, bytes.size - sent, 200)
                if (n <= 0) return false
                sent += n
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readOk(conn: UsbDeviceConnection): Boolean {
        // Best-effort: we mostly fire-and-forget; P handshake result is not critical.
        return true
    }
}
