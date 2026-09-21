package com.pipidu.tiny1b.core.uvc

/** Parsed USB / UVC descriptors from [android.hardware.usb.UsbDeviceConnection.getRawDescriptors]. */
data class UvcEndpointDesc(
    val address: Int,
    val attributes: Int,
    val maxPacketSizeRaw: Int,
    val interval: Int,
) {
    val directionIn: Boolean get() = address and 0x80 != 0
    val transferType: Int get() = attributes and 0x03
    val isBulk: Boolean get() = transferType == UvcDescriptors.XFER_BULK
    val isIsochronous: Boolean get() = transferType == UvcDescriptors.XFER_ISOC
    /** High-speed isoc: bits 10:0 size, bits 12:11 extra transactions per microframe. */
    val maxPacketSize: Int
        get() {
            val size = maxPacketSizeRaw and 0x07FF
            val extra = (maxPacketSizeRaw shr 11) and 0x03
            return size * (extra + 1)
        }
}

data class UvcAltSetting(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubClass: Int,
    val endpoints: List<UvcEndpointDesc>,
)

data class UvcFrameDesc(
    val formatIndex: Int,
    val frameIndex: Int,
    val width: Int,
    val height: Int,
    val guid: ByteArray,
    val defaultInterval: Int,
    val interfaceNumber: Int,
) {
    val frameBytes: Int get() = width * height * 2
    val isYuy2: Boolean get() = guid.contentEquals(GUID_YUY2)
}

data class UvcParsedDevice(
    val vendorId: Int,
    val productId: Int,
    val alts: List<UvcAltSetting>,
    val frames: List<UvcFrameDesc>,
    val controlInterfaceNumber: Int?,
    val streamingInterfaceNumber: Int?,
) {
    fun dump(): String = buildString {
        append("vid=${vendorId.toString(16)} pid=${productId.toString(16)}")
        append(" vc=${controlInterfaceNumber ?: -1} vs=${streamingInterfaceNumber ?: -1}")
        append(" frames=${frames.joinToString { "${it.width}x${it.height} fmt=${it.formatIndex}/${it.frameIndex}" }}")
        alts.forEach { alt ->
            append(" [if=${alt.interfaceNumber} alt=${alt.alternateSetting} class=${alt.interfaceClass}/${alt.interfaceSubClass}")
            alt.endpoints.forEach { ep ->
                append(" ep=${ep.address.toString(16)} t=${ep.transferType} max=${ep.maxPacketSize}")
            }
            append("]")
        }
    }
}

data class UvcStreamPlan(
    val controlInterfaceNumber: Int,
    val streamingInterfaceNumber: Int,
    val streamingAlt: Int,
    val formatIndex: Int,
    val frameIndex: Int,
    val width: Int,
    val height: Int,
    val frameBytes: Int,
    val endpointAddress: Int,
    val bulk: Boolean,
    val maxPacketSize: Int,
)

object UvcDescriptors {
    const val CLASS_VIDEO = 14
    const val SC_VIDEOCONTROL = 1
    const val SC_VIDEOSTREAMING = 2
    const val XFER_ISOC = 1
    const val XFER_BULK = 2
    const val DT_DEVICE = 1
    const val DT_CONFIG = 2
    const val DT_INTERFACE = 4
    const val DT_ENDPOINT = 5
    const val DT_IAD = 11
    const val DT_CS_INTERFACE = 0x24
    const val VS_INPUT_HEADER = 0x01
    const val VS_FORMAT_UNCOMPRESSED = 0x04
    const val VS_FRAME_UNCOMPRESSED = 0x05
    const val VS_FORMAT_MJPEG = 0x06
    const val VS_FRAME_MJPEG = 0x07

    const val VS_PROBE_CONTROL = 0x01
    const val VS_COMMIT_CONTROL = 0x02
    const val SET_CUR = 0x01
    const val GET_CUR = 0x81
    const val GET_LEN = 0x85
    const val RT_CLASS_INTERFACE_OUT = 0x21
    const val RT_CLASS_INTERFACE_IN = 0xA1

    fun parse(raw: ByteArray): UvcParsedDevice {
        var vendorId = 0
        var productId = 0
        var offset = 0
        var curIf = 0
        var curAlt = 0
        var curClass = 0
        var curSub = 0
        var curFormatIndex = 0
        var curGuid = ByteArray(16)
        val alts = ArrayList<UvcAltSetting>()
        val endpoints = ArrayList<UvcEndpointDesc>()
        val frames = ArrayList<UvcFrameDesc>()
        var vcIf: Int? = null
        var vsIf: Int? = null

        fun flushAlt() {
            if (curClass == 0 && endpoints.isEmpty() && alts.none { it.interfaceNumber == curIf && it.alternateSetting == curAlt }) {
                return
            }
            if (alts.any { it.interfaceNumber == curIf && it.alternateSetting == curAlt }) return
            alts += UvcAltSetting(curIf, curAlt, curClass, curSub, endpoints.toList())
            endpoints.clear()
        }

        while (offset + 2 <= raw.size) {
            val len = raw.u8(offset)
            if (len < 2 || offset + len > raw.size) break
            val type = raw.u8(offset + 1)
            when (type) {
                DT_DEVICE -> {
                    if (len >= 10) {
                        vendorId = raw.u16(offset + 8)
                        productId = raw.u16(offset + 10)
                    }
                }
                DT_INTERFACE -> {
                    if (alts.isNotEmpty() || endpoints.isNotEmpty() || curClass != 0) {
                        flushAlt()
                    } else {
                        endpoints.clear()
                    }
                    if (len >= 9) {
                        curIf = raw.u8(offset + 2)
                        curAlt = raw.u8(offset + 3)
                        curClass = raw.u8(offset + 5)
                        curSub = raw.u8(offset + 6)
                        if (curClass == CLASS_VIDEO && curSub == SC_VIDEOCONTROL) vcIf = curIf
                        if (curClass == CLASS_VIDEO && curSub == SC_VIDEOSTREAMING) vsIf = curIf
                    }
                    // Start collecting endpoints for this alt.
                }
                DT_ENDPOINT -> {
                    if (len >= 7) {
                        endpoints += UvcEndpointDesc(
                            address = raw.u8(offset + 2),
                            attributes = raw.u8(offset + 3),
                            maxPacketSizeRaw = raw.u16(offset + 4),
                            interval = raw.u8(offset + 6),
                        )
                    }
                }
                DT_CS_INTERFACE -> {
                    if (len >= 3 && curClass == CLASS_VIDEO && curSub == SC_VIDEOSTREAMING) {
                        when (raw.u8(offset + 2)) {
                            VS_FORMAT_UNCOMPRESSED -> {
                                if (len >= 21) {
                                    curFormatIndex = raw.u8(offset + 3)
                                    curGuid = raw.copyOfRange(offset + 5, offset + 21)
                                }
                            }
                            VS_FRAME_UNCOMPRESSED -> {
                                if (len >= 25) {
                                    frames += UvcFrameDesc(
                                        formatIndex = curFormatIndex,
                                        frameIndex = raw.u8(offset + 3),
                                        width = raw.u16(offset + 5),
                                        height = raw.u16(offset + 7),
                                        guid = curGuid.copyOf(),
                                        defaultInterval = raw.u32(offset + 21),
                                        interfaceNumber = curIf,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            offset += len
        }
        flushAlt()
        if (vcIf == null) {
            vcIf = alts.firstOrNull { it.interfaceClass == CLASS_VIDEO && it.interfaceSubClass == SC_VIDEOCONTROL }?.interfaceNumber
        }
        if (vsIf == null) {
            vsIf = alts.firstOrNull { it.interfaceClass == CLASS_VIDEO && it.interfaceSubClass == SC_VIDEOSTREAMING }?.interfaceNumber
                ?: frames.firstOrNull()?.interfaceNumber
        }
        return UvcParsedDevice(vendorId, productId, alts, frames, vcIf, vsIf)
    }

    /**
     * Prefer uncompressed YUY2 at [width]×[height]. Prefer a bulk IN endpoint
     * (Android Java can stream bulk reliably); otherwise the largest isoc IN alt.
     */
    fun plan(
        parsed: UvcParsedDevice,
        width: Int,
        height: Int,
        frameBytes: Int,
    ): UvcStreamPlan? {
        val vs = parsed.streamingInterfaceNumber ?: return null
        val vc = parsed.controlInterfaceNumber ?: 0
        val frame = parsed.frames.firstOrNull { it.isYuy2 && it.width == width && it.height == height }
            ?: parsed.frames.firstOrNull { it.isYuy2 && it.frameBytes == frameBytes }
            ?: parsed.frames.firstOrNull { it.width == width && it.height == height }
            ?: parsed.frames.firstOrNull { it.frameBytes == frameBytes }
            ?: return null
        val vsAlts = parsed.alts.filter { it.interfaceNumber == vs }
        val bulkEp = vsAlts.asSequence()
            .flatMap { alt -> alt.endpoints.map { alt to it } }
            .firstOrNull { (_, ep) -> ep.directionIn && ep.isBulk }
        if (bulkEp != null) {
            val (alt, ep) = bulkEp
            return UvcStreamPlan(
                controlInterfaceNumber = vc,
                streamingInterfaceNumber = vs,
                streamingAlt = alt.alternateSetting,
                formatIndex = frame.formatIndex,
                frameIndex = frame.frameIndex,
                width = frame.width,
                height = frame.height,
                frameBytes = frame.frameBytes,
                endpointAddress = ep.address,
                bulk = true,
                maxPacketSize = ep.maxPacketSize,
            )
        }
        val isoc = vsAlts.asSequence()
            .flatMap { alt -> alt.endpoints.map { alt to it } }
            .filter { (_, ep) -> ep.directionIn && ep.isIsochronous }
            .maxByOrNull { it.second.maxPacketSize }
            ?: return null
        val (alt, ep) = isoc
        return UvcStreamPlan(
            controlInterfaceNumber = vc,
            streamingInterfaceNumber = vs,
            streamingAlt = alt.alternateSetting,
            formatIndex = frame.formatIndex,
            frameIndex = frame.frameIndex,
            width = frame.width,
            height = frame.height,
            frameBytes = frame.frameBytes,
            endpointAddress = ep.address,
            bulk = false,
            maxPacketSize = ep.maxPacketSize,
        )
    }
}

val GUID_YUY2: ByteArray = byteArrayOf(
    0x59, 0x55, 0x59, 0x32, 0x00, 0x00, 0x10, 0x00,
    0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
)

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u32(i: Int): Int = u16(i) or (u16(i + 2) shl 16)
