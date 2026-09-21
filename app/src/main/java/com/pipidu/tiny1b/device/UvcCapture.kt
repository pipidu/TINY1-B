package com.pipidu.tiny1b.device

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import com.pipidu.tiny1b.core.Tiny1BFormat
import com.pipidu.tiny1b.core.uvc.UvcDescriptors
import com.pipidu.tiny1b.core.uvc.UvcPayloadAssembler
import com.pipidu.tiny1b.core.uvc.UvcProbe
import com.pipidu.tiny1b.core.uvc.UvcStreamPlan
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tiny1-B UVC capture over [UsbDeviceConnection]: class probe/commit, then
 * bulk IN or USBFS isochronous. No vendor JNI.
 */
class UvcCapture {
    private val running = AtomicBoolean(false)
    private val claimed = ArrayList<UsbInterface>()
    private var connection: UsbDeviceConnection? = null
    private var streamThread: Thread? = null
    private var isoReader: IsochronousReader? = null
    @Volatile private var streamingInterface: UsbInterface? = null
    @Volatile private var streamingAlt0: UsbInterface? = null

    /**
     * @return null on success, otherwise a Chinese error for the connect card.
     */
    fun start(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ): String? {
        stop()
        this.connection = connection
        val raw = runCatching { connection.rawDescriptors }.getOrNull()
        val parsed = raw?.let { runCatching { UvcDescriptors.parse(it) }.getOrNull() }
        val dump = parsed?.dump() ?: describeDevice(device)
        Log.i(TAG, "UVC descriptors: $dump")
        val plan = (parsed?.let {
            UvcDescriptors.plan(it, Tiny1BFormat.UVC_WIDTH, Tiny1BFormat.UVC_HEIGHT, Tiny1BFormat.UVC_FRAME_BYTES)
        } ?: fallbackPlan(device))
            ?: return "未找到 Tiny1-B 的 YUYV 256×384 端点。$dump"
        Log.i(
            TAG,
            "plan bulk=${plan.bulk} alt=${plan.streamingAlt} fmt=${plan.formatIndex}/${plan.frameIndex} " +
                "ep=0x${plan.endpointAddress.toString(16)} max=${plan.maxPacketSize}",
        )
        val vc = preferredClaimInterface(device, plan.controlInterfaceNumber)
            ?: findInterfaceByClass(device, UvcDescriptors.CLASS_VIDEO, UvcDescriptors.SC_VIDEOCONTROL)
        val vs0 = preferredClaimInterface(device, plan.streamingInterfaceNumber)
            ?: findInterfaceByClass(device, UvcDescriptors.CLASS_VIDEO, UvcDescriptors.SC_VIDEOSTREAMING)
        val vsStream = findInterface(device, plan.streamingInterfaceNumber, plan.streamingAlt) ?: vs0
        if (vc == null || vs0 == null || vsStream == null) {
            return "未找到 UVC 控制/流接口（VC=${plan.controlInterfaceNumber} VS=${plan.streamingInterfaceNumber} alt=${plan.streamingAlt}）。$dump"
        }
        val fd = runCatching { connection.fileDescriptor }.getOrDefault(0)
        detachKernelDriver(connection, vc.id)
        detachKernelDriver(connection, vs0.id)
        val claimVc = claim(connection, vc)
        if (!claimVc.ok) {
            return "无法占用 UVC 控制接口。${claimVc.detail} fd=$fd。请关闭其它相机应用后重新插拔 Tiny1-B。"
        }
        val claimVs = claim(connection, vs0)
        if (!claimVs.ok) {
            return "无法占用 UVC 流接口。${claimVs.detail} fd=$fd。请关闭其它相机应用后重新插拔 Tiny1-B。"
        }
        if (vsStream.id != vs0.id) {
            val extra = claim(connection, vsStream)
            if (!extra.ok) {
                Log.w(TAG, "claim extra VS interface ${vsStream.id} failed ${extra.detail}")
            }
        }
        val negotiated = negotiate(connection, plan.streamingInterfaceNumber, plan)
        val probe = negotiated.probe
            ?: return "UVC 协商（PROBE/COMMIT）失败。${negotiated.detail} $dump"
        val payloadSize = probe.maxPayloadTransferSize
            .takeIf { it in 64..0x10000 }
            ?: if (plan.bulk) 16384 else plan.maxPacketSize
        if (plan.streamingAlt != 0 || vsStream.alternateSetting != vs0.alternateSetting) {
            val setAlt = runCatching { connection.setInterface(vsStream) }
            if (!setAlt.getOrDefault(false)) {
                val err = setAlt.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "false"
                return "无法切换到 UVC 取流接口 alt=${plan.streamingAlt}（setInterface=$err）。$dump"
            }
        }
        streamingInterface = vsStream
        streamingAlt0 = vs0
        val endpoint = findEndpoint(vsStream, plan)
            ?: findEndpoint(vs0, plan)
            ?: return "UVC 接口上没有匹配的输入端点。$dump"
        runCatching {
            if (Usbfs.available) {
                val haltFd = connection.fileDescriptor
                if (haltFd > 0) Usbfs.nativeClearHalt(haltFd, endpoint.address)
            }
        }
        val assembler = UvcPayloadAssembler(plan.frameBytes, onFrame)
        running.set(true)
        val firstFrameDeadline = android.os.SystemClock.elapsedRealtime() + FIRST_FRAME_MS
        streamThread = thread(name = "tiny1b-uvc", isDaemon = true) {
            try {
                if (plan.bulk || endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    streamBulk(connection, endpoint, payloadSize, assembler, firstFrameDeadline, onError)
                } else {
                    streamIso(connection, endpoint, plan.maxPacketSize, assembler, firstFrameDeadline, onError)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "stream", error)
                if (running.get()) {
                    onError("UVC 取流中断：${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
        return null
    }

    fun stop() {
        running.set(false)
        isoReader?.stop()
        isoReader = null
        val worker = streamThread
        streamThread = null
        if (worker != null && worker !== Thread.currentThread()) {
            worker.interrupt()
            runCatching { worker.join(800) }
        }
        val conn = connection
        val alt0 = streamingAlt0
        if (conn != null && alt0 != null) {
            runCatching { conn.setInterface(alt0) }
        }
        streamingInterface = null
        streamingAlt0 = null
        if (conn != null) {
            for (iface in claimed.asReversed()) {
                runCatching { conn.releaseInterface(iface) }
            }
        }
        claimed.clear()
        connection = null
    }

    private fun streamBulk(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        payloadSize: Int,
        assembler: UvcPayloadAssembler,
        firstFrameDeadline: Long,
        onError: (String) -> Unit,
    ) {
        val buf = ByteArray(payloadSize.coerceIn(endpoint.maxPacketSize, 16384))
        while (running.get()) {
            val n = connection.bulkTransfer(endpoint, buf, buf.size, 1000)
            if (n > 0) {
                assembler.offer(buf, n)
            } else if (n < 0 && running.get()) {
                Log.w(TAG, "bulkTransfer=$n")
            }
            if (assembler.completeFrames == 0L &&
                running.get() &&
                android.os.SystemClock.elapsedRealtime() > firstFrameDeadline
            ) {
                onError("已打开 USB，但未收到 UVC 画面。请重新插拔 Tiny1-B。")
                return
            }
        }
    }

    private fun streamIso(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        packetSize: Int,
        assembler: UvcPayloadAssembler,
        firstFrameDeadline: Long,
        onError: (String) -> Unit,
    ) {
        val fd = connection.fileDescriptor
        if (fd <= 0) {
            onError("USB 文件描述符无效（fd=$fd）。")
            return
        }
        if (!Usbfs.available) {
            onError("等时传输需要 USBFS 助手，当前未能加载。")
            return
        }
        val pkt = packetSize.coerceAtLeast(endpoint.maxPacketSize).coerceAtLeast(512)
        val reader = IsochronousReader(fd, endpoint.address, pkt, assembler)
        isoReader = reader
        val watchdog = thread(name = "tiny1b-uvc-watch", isDaemon = true) {
            while (running.get()) {
                if (assembler.completeFrames == 0L &&
                    running.get() &&
                    android.os.SystemClock.elapsedRealtime() > firstFrameDeadline
                ) {
                    onError("已打开 USB，但未收到 UVC 画面。请重新插拔 Tiny1-B。")
                    reader.stop()
                    return@thread
                }
                if (assembler.completeFrames > 0L) return@thread
                Thread.sleep(200)
            }
        }
        try {
            reader.loop { running.get() }
        } finally {
            watchdog.interrupt()
        }
    }

    private fun negotiate(
        connection: UsbDeviceConnection,
        interfaceNumber: Int,
        plan: UvcStreamPlan,
    ): NegotiateResult {
        val reportedLen = getLen(connection, interfaceNumber)
        val attempts = ArrayList<String>()
        for (size in UvcProbe.candidateSizes(reportedLen)) {
            val probe = UvcProbe.empty(size)
            val get1 = controlTransfer(
                connection,
                UvcDescriptors.RT_CLASS_INTERFACE_IN,
                UvcDescriptors.GET_CUR,
                UvcDescriptors.VS_PROBE_CONTROL,
                interfaceNumber,
                probe.bytes,
            )
            probe.formatIndex = plan.formatIndex
            probe.frameIndex = plan.frameIndex
            if (probe.maxVideoFrameSize <= 0) probe.maxVideoFrameSize = plan.frameBytes
            if (probe.frameInterval <= 0) probe.frameInterval = 400_000
            if (probe.maxPayloadTransferSize <= 0) {
                probe.maxPayloadTransferSize = if (plan.bulk) 16384 else plan.maxPacketSize
            }
            probe.hintFrameInterval()
            val setProbe = controlTransfer(
                connection,
                UvcDescriptors.RT_CLASS_INTERFACE_OUT,
                UvcDescriptors.SET_CUR,
                UvcDescriptors.VS_PROBE_CONTROL,
                interfaceNumber,
                probe.bytes,
            )
            if (setProbe < 0) {
                Log.w(TAG, "SET_CUR PROBE size=$size n=$setProbe, continuing")
            }
            val get2 = controlTransfer(
                connection,
                UvcDescriptors.RT_CLASS_INTERFACE_IN,
                UvcDescriptors.GET_CUR,
                UvcDescriptors.VS_PROBE_CONTROL,
                interfaceNumber,
                probe.bytes,
            )
            probe.formatIndex = plan.formatIndex
            probe.frameIndex = plan.frameIndex
            if (probe.maxVideoFrameSize <= 0) probe.maxVideoFrameSize = plan.frameBytes
            val commit = controlTransfer(
                connection,
                UvcDescriptors.RT_CLASS_INTERFACE_OUT,
                UvcDescriptors.SET_CUR,
                UvcDescriptors.VS_COMMIT_CONTROL,
                interfaceNumber,
                probe.bytes,
            )
            attempts += "size=$size GET_LEN=$reportedLen GET_CUR=$get1/$get2 SET_PROBE=$setProbe COMMIT=$commit"
            if (commit > 0) {
                Log.i(TAG, "COMMIT ok ${attempts.last()}")
                return NegotiateResult(probe, attempts.last())
            }
            Log.e(TAG, "SET_CUR COMMIT failed ${attempts.last()}")
        }
        return NegotiateResult(null, attempts.joinToString("；"))
    }

    private fun getLen(connection: UsbDeviceConnection, interfaceNumber: Int): Int {
        val buf = ByteArray(2)
        val n = controlTransfer(
            connection,
            UvcDescriptors.RT_CLASS_INTERFACE_IN,
            UvcDescriptors.GET_LEN,
            UvcDescriptors.VS_PROBE_CONTROL,
            interfaceNumber,
            buf,
        )
        if (n < 1) return 0
        var len = buf[0].toInt() and 0xFF
        if (n >= 2) len = len or ((buf[1].toInt() and 0xFF) shl 8)
        return len
    }

    private fun controlTransfer(
        connection: UsbDeviceConnection,
        requestType: Int,
        request: Int,
        selector: Int,
        interfaceNumber: Int,
        data: ByteArray,
    ): Int {
        return try {
            connection.controlTransfer(
                requestType,
                request,
                selector shl 8,
                interfaceNumber,
                data,
                data.size,
                TIMEOUT_MS,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "controlTransfer type=0x${requestType.toString(16)} req=0x${request.toString(16)}", error)
            -1
        }
    }

    private fun detachKernelDriver(connection: UsbDeviceConnection, interfaceNumber: Int) {
        val fd = runCatching { connection.fileDescriptor }.getOrDefault(0)
        if (!Usbfs.available || fd <= 0) return
        val rc = runCatching { Usbfs.nativeDisconnect(fd, interfaceNumber) }.getOrDefault(-1)
        Log.i(TAG, "USBDEVFS_DISCONNECT if=$interfaceNumber ${Usbfs.errnoName(rc)}")
    }

    private fun claim(connection: UsbDeviceConnection, iface: UsbInterface): ClaimResult {
        if (claimed.any { it.id == iface.id }) {
            return ClaimResult(true, "already claimed id=${iface.id}")
        }
        val info = "id=${iface.id} alt=${iface.alternateSetting} class=${iface.interfaceClass}/${iface.interfaceSubclass}"
        val first = runCatching { connection.claimInterface(iface, true) }
        if (first.getOrDefault(false)) {
            claimed += iface
            return ClaimResult(true, "claimInterface(force) $info")
        }
        val firstErr = first.exceptionOrNull()?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "false"
        val fd = runCatching { connection.fileDescriptor }.getOrDefault(0)
        var disconnectRc = 0
        var nativeClaimRc = 0
        if (Usbfs.available && fd > 0) {
            disconnectRc = runCatching { Usbfs.nativeDisconnect(fd, iface.id) }.getOrDefault(-1)
            val second = runCatching { connection.claimInterface(iface, true) }
            if (second.getOrDefault(false)) {
                claimed += iface
                return ClaimResult(true, "disconnect=${Usbfs.errnoName(disconnectRc)} then claimInterface $info")
            }
            nativeClaimRc = runCatching { Usbfs.nativeClaimInterface(fd, iface.id) }.getOrDefault(-1)
            if (nativeClaimRc == 0) {
                claimed += iface
                return ClaimResult(true, "USBDEVFS_CLAIMINTERFACE $info")
            }
            val third = runCatching { connection.claimInterface(iface, true) }
            if (third.getOrDefault(false)) {
                claimed += iface
                return ClaimResult(true, "nativeClaim=${Usbfs.errnoName(nativeClaimRc)} then claimInterface $info")
            }
        }
        return ClaimResult(
            false,
            "$info fd=$fd claim=$firstErr disconnect=${Usbfs.errnoName(disconnectRc)} nativeClaim=${Usbfs.errnoName(nativeClaimRc)}",
        )
    }

    private fun preferredClaimInterface(device: UsbDevice, id: Int): UsbInterface? {
        findInterface(device, id, 0)?.let { return it }
        var found: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id != id) continue
            if (found == null || iface.alternateSetting < found.alternateSetting) found = iface
        }
        return found
    }

    private fun findInterface(device: UsbDevice, id: Int, alt: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == id && iface.alternateSetting == alt) return iface
        }
        return null
    }

    private fun findInterfaceByClass(device: UsbDevice, clazz: Int, sub: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == clazz && iface.interfaceSubclass == sub && iface.alternateSetting == 0) {
                return iface
            }
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == clazz && iface.interfaceSubclass == sub) return iface
        }
        return null
    }

    private fun findEndpoint(iface: UsbInterface, plan: UvcStreamPlan): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.address == plan.endpointAddress) return ep
        }
        val want = if (plan.bulk) UsbConstants.USB_ENDPOINT_XFER_BULK else UsbConstants.USB_ENDPOINT_XFER_ISOC
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == want) return ep
        }
        return null
    }

    private fun fallbackPlan(device: UsbDevice): UvcStreamPlan? {
        var vc = 0
        var vs = -1
        var bulk: Triple<Int, Int, Int>? = null
        var isoc: Triple<Int, Int, Int>? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UvcDescriptors.CLASS_VIDEO) continue
            if (iface.interfaceSubclass == UvcDescriptors.SC_VIDEOCONTROL) vc = iface.id
            if (iface.interfaceSubclass != UvcDescriptors.SC_VIDEOSTREAMING) continue
            vs = iface.id
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.direction != UsbConstants.USB_DIR_IN) continue
                val triple = Triple(iface.alternateSetting, ep.address, ep.maxPacketSize)
                when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> if (bulk == null) bulk = triple
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> {
                        if (isoc == null || ep.maxPacketSize > isoc!!.third) isoc = triple
                    }
                }
            }
        }
        if (vs < 0) return null
        val chosen = bulk ?: isoc ?: return null
        return UvcStreamPlan(
            controlInterfaceNumber = vc,
            streamingInterfaceNumber = vs,
            streamingAlt = chosen.first,
            formatIndex = 1,
            frameIndex = 1,
            width = Tiny1BFormat.UVC_WIDTH,
            height = Tiny1BFormat.UVC_HEIGHT,
            frameBytes = Tiny1BFormat.UVC_FRAME_BYTES,
            endpointAddress = chosen.second,
            bulk = bulk != null,
            maxPacketSize = chosen.third,
        )
    }

    private fun describeDevice(device: UsbDevice): String = buildString {
        append("ifaces=${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            append(" [id=${iface.id} alt=${iface.alternateSetting} class=${iface.interfaceClass}/${iface.interfaceSubclass}")
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                append(" ep=${Integer.toHexString(ep.address)} t=${ep.type} max=${ep.maxPacketSize}")
            }
            append("]")
        }
    }

    companion object {
        private const val TAG = "UvcCapture"
        private const val TIMEOUT_MS = 1000
        private const val FIRST_FRAME_MS = 8000L
    }

    private data class ClaimResult(val ok: Boolean, val detail: String)
    private data class NegotiateResult(val probe: UvcProbe?, val detail: String)
}
