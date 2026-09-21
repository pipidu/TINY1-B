package com.pipidu.tiny1b.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Encodes live thermal [Bitmap]s to an MP4 (H.264, no audio) in the gallery.
 */
internal class ThermalRecorder(private val context: Context) {
    private val lock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var sink: VideoSink? = null
    private var track = -1
    private var muxerStarted = false
    private var startNs = 0L
    private var encW = 0
    private var encH = 0
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    var recording: Boolean = false
        private set

    fun start(srcW: Int, srcH: Int) {
        synchronized(lock) {
            if (recording) return
            val w = align16(srcW.coerceAtLeast(16))
            val h = align16(srcH.coerceAtLeast(16))
            val created = CaptureStore.createVideoSink(context)
            try {
                val mux = if (created.pfd != null) {
                    MediaMuxer(created.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                } else {
                    MediaMuxer(created.file!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 4).coerceIn(250_000, 6_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val input = encoder.createInputSurface()
                encoder.start()
                codec = encoder
                surface = input
                muxer = mux
                sink = created
                encW = w
                encH = h
                track = -1
                muxerStarted = false
                startNs = SystemClock.elapsedRealtimeNanos()
                recording = true
            } catch (error: Throwable) {
                CaptureStore.finishVideo(context, created, success = false)
                runCatching { created.pfd?.close() }
                releaseLocked()
                throw error
            }
        }
    }

    fun offer(bitmap: Bitmap) {
        synchronized(lock) {
            if (!recording || bitmap.isRecycled) return
            val input = surface ?: return
            drainLocked(timeoutUs = 0)
            val canvas = try {
                input.lockCanvas(null)
            } catch (error: Throwable) {
                Log.w(TAG, "lockCanvas", error)
                return
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, null, Rect(0, 0, encW, encH), paint)
            } finally {
                runCatching { input.unlockCanvasAndPost(canvas) }
            }
            drainLocked(timeoutUs = 0)
        }
    }

    fun stop(): android.net.Uri? {
        synchronized(lock) {
            if (!recording) return null
            recording = false
            val currentSink = sink
            var ok = false
            try {
                runCatching { codec?.signalEndOfInputStream() }
                drainLocked(timeoutUs = 10_000, untilEos = true)
                if (muxerStarted) {
                    runCatching { muxer?.stop() }
                    ok = true
                }
            } catch (error: Throwable) {
                Log.e(TAG, "stop", error)
                ok = false
            } finally {
                releaseLocked()
            }
            if (currentSink != null) {
                CaptureStore.finishVideo(context, currentSink, success = ok)
            }
            if (!ok) {
                throw IllegalStateException("录像写入未完成")
            }
            return currentSink?.uri ?: currentSink?.file?.let { android.net.Uri.fromFile(it) }
        }
    }

    private fun drainLocked(timeoutUs: Long, untilEos: Boolean = false) {
        val encoder = codec ?: return
        val mux = muxer ?: return
        val deadline = SystemClock.elapsedRealtime() + if (untilEos) 2_000L else 0L
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos || SystemClock.elapsedRealtime() >= deadline) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) continue
                    track = mux.addTrack(encoder.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val encoded: ByteBuffer? = encoder.getOutputBuffer(index)
                    if (encoded != null && bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        muxerStarted
                    ) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        if (bufferInfo.presentationTimeUs <= 0L) {
                            bufferInfo.presentationTimeUs =
                                (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000L
                        }
                        mux.writeSampleData(track, encoded, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun releaseLocked() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { surface?.release() }
        surface = null
        runCatching { muxer?.release() }
        muxer = null
        runCatching { sink?.pfd?.close() }
        sink = null
        muxerStarted = false
        track = -1
    }

    companion object {
        private const val TAG = "ThermalRecorder"
        private const val FRAME_RATE = 20
        private fun align16(n: Int): Int = (n + 15) and 15.inv()
    }
}
