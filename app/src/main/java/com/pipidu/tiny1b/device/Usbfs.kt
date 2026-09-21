package com.pipidu.tiny1b.device

import android.util.Log
import com.pipidu.tiny1b.core.uvc.UvcPayloadAssembler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny USBFS helper: multi-packet isochronous URBs on the already-open
 * [android.hardware.usb.UsbDeviceConnection] file descriptor. Not vendor UVC.
 */
object Usbfs {
    @JvmStatic val available: Boolean

    init {
        available = try {
            System.loadLibrary("tiny1busb")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "tiny1busb not loaded", error)
            false
        }
    }

    @JvmStatic external fun nativeAddress(buffer: ByteBuffer): Long
    @JvmStatic external fun nativeSubmit(fd: Int, urb: ByteBuffer): Int
    @JvmStatic external fun nativeReap(fd: Int, timeoutMs: Int): Long
    @JvmStatic external fun nativeDiscard(fd: Int, urb: ByteBuffer): Int
    @JvmStatic external fun nativeClearHalt(fd: Int, endpoint: Int): Int
    @JvmStatic external fun nativeDisconnect(fd: Int, interfaceNumber: Int): Int
    @JvmStatic external fun nativeClaimInterface(fd: Int, interfaceNumber: Int): Int

    fun errnoName(errno: Int): String {
        val code = if (errno < 0) -errno else errno
        val name = when (code) {
            0 -> "ok"
            1 -> "EPERM"
            2 -> "ENOENT"
            5 -> "EIO"
            13 -> "EACCES"
            16 -> "EBUSY"
            19 -> "ENODEV"
            22 -> "EINVAL"
            32 -> "EPIPE"
            110 -> "ETIMEDOUT"
            else -> "errno"
        }
        return "$name($code)"
    }

    private const val TAG = "Usbfs"
}

/**
 * High-speed UVC isochronous reader: several URBs × 32 packets so Java does
 * not need 8000 completions per second.
 */
class IsochronousReader(
    private val fd: Int,
    private val endpointAddress: Int,
    private val packetSize: Int,
    private val assembler: UvcPayloadAssembler,
) {
    private val running = AtomicBoolean(false)
    private val lp64 = Long.SIZE_BYTES == 8
    private val urbBase = if (lp64) 56 else 44
    private val ptrSize = if (lp64) 8 else 4
    private val offBuffer = if (lp64) 16 else 12
    private val offBufferLength = offBuffer + ptrSize
    private val offActual = offBufferLength + 4
    private val offStartFrame = offActual + 4
    private val offNumPackets = offStartFrame + 4
    private val offErrorCount = offNumPackets + 4
    private val offSignr = offErrorCount + 4
    private val offUser = offSignr + 4
    private val offIso = offUser + ptrSize

    private inner class Urb(val index: Int) {
        val data: ByteBuffer = ByteBuffer.allocateDirect(PACKETS * packetSize).order(ByteOrder.LITTLE_ENDIAN)
        val urb: ByteBuffer = ByteBuffer.allocateDirect(offIso + PACKETS * 12).order(ByteOrder.LITTLE_ENDIAN)
        val urbAddr: Long = Usbfs.nativeAddress(urb)
        val dataAddr: Long = Usbfs.nativeAddress(data)

        fun prepare() {
            urb.order(ByteOrder.LITTLE_ENDIAN)
            urb.put(0, 0) // USBDEVFS_URB_TYPE_ISO
            urb.put(1, endpointAddress.toByte())
            urb.putShort(2, 0)
            urb.putInt(4, 0)
            urb.putInt(8, USBDEVFS_URB_ISO_ASAP)
            putPtr(urb, offBuffer, dataAddr)
            urb.putInt(offBufferLength, PACKETS * packetSize)
            urb.putInt(offActual, 0)
            urb.putInt(offStartFrame, 0)
            urb.putInt(offNumPackets, PACKETS)
            urb.putInt(offErrorCount, 0)
            urb.putInt(offSignr, 0)
            putPtr(urb, offUser, index.toLong())
            var desc = offIso
            for (i in 0 until PACKETS) {
                urb.putInt(desc, packetSize)
                urb.putInt(desc + 4, 0)
                urb.putInt(desc + 8, 0)
                desc += 12
            }
        }

        fun harvest() {
            var desc = offIso
            var dataOff = 0
            val tmp = ByteArray(packetSize)
            for (i in 0 until PACKETS) {
                val actual = urb.getInt(desc + 4)
                val status = urb.getInt(desc + 8)
                if (status == 0 && actual > 0) {
                    val n = actual.coerceAtMost(packetSize)
                    data.position(dataOff)
                    data.get(tmp, 0, n)
                    assembler.offer(tmp, n)
                }
                dataOff += packetSize
                desc += 12
            }
        }
    }

    fun loop(alive: () -> Boolean) {
        if (!Usbfs.available) throw IllegalStateException("USBFS 等时传输库未加载")
        running.set(true)
        val urbs = Array(URB_COUNT) { Urb(it) }
        val byAddr = HashMap<Long, Urb>()
        try {
            for (urb in urbs) {
                urb.prepare()
                val rc = Usbfs.nativeSubmit(fd, urb.urb)
                if (rc < 0) {
                    throw IllegalStateException("SUBMITURB 失败 errno=${-rc}")
                }
                byAddr[urb.urbAddr] = urb
            }
            while (alive() && running.get()) {
                val addr = Usbfs.nativeReap(fd, 400)
                if (addr < 0) {
                    val err = (-addr).toInt()
                    if (err == ETIMEDOUT || err == EAGAIN || err == EINTR) continue
                    if (err == ENODEV) throw IllegalStateException("USB 设备已断开")
                    Log.w(TAG, "REAPURB errno=$err")
                    continue
                }
                val urb = byAddr[addr] ?: continue
                urb.harvest()
                urb.prepare()
                val rc = Usbfs.nativeSubmit(fd, urb.urb)
                if (rc < 0) {
                    val err = -rc
                    if (err == ENODEV) throw IllegalStateException("USB 设备已断开")
                    Log.w(TAG, "re-SUBMITURB errno=$err")
                }
            }
        } finally {
            running.set(false)
            for (urb in urbs) {
                runCatching { Usbfs.nativeDiscard(fd, urb.urb) }
            }
        }
    }

    fun stop() {
        running.set(false)
    }

    private fun putPtr(buf: ByteBuffer, offset: Int, value: Long) {
        if (lp64) buf.putLong(offset, value) else buf.putInt(offset, value.toInt())
    }

    companion object {
        private const val TAG = "IsochronousReader"
        private const val PACKETS = 32
        private const val URB_COUNT = 4
        private const val USBDEVFS_URB_ISO_ASAP = 2
        private const val EINTR = 4
        private const val EAGAIN = 11
        private const val ENODEV = 19
        private const val ETIMEDOUT = 110
    }
}
