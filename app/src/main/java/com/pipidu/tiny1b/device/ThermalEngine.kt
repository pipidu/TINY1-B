package com.pipidu.tiny1b.device

import android.graphics.Bitmap
import android.os.SystemClock
import com.pipidu.tiny1b.core.FrameParser
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.core.MeasurementModel
import com.pipidu.tiny1b.core.MeasurementSnapshot
import com.pipidu.tiny1b.core.PointKind
import com.pipidu.tiny1b.core.PaletteId
import com.pipidu.tiny1b.core.Palettes
import com.pipidu.tiny1b.core.RenderedFrame
import com.pipidu.tiny1b.core.SuperResolution
import com.pipidu.tiny1b.core.SyntheticScene
import com.pipidu.tiny1b.core.ThermalPlanes
import com.pipidu.tiny1b.core.Tiny1BFormat
import com.pipidu.tiny1b.data.AppSettings
import com.zz.infisense.camera.IFrameCallback
import com.zz.infisense.camera.UVCCamera
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DeviceStatus {
    Searching,
    PermissionNeeded,
    PermissionDenied,
    Connecting,
    Live,
    Sample,
    JniUnavailable,
    Error,
}

data class EngineState(
    val status: DeviceStatus = DeviceStatus.Searching,
    val statusDetail: String = "",
    val bitmap: Bitmap? = null,
    val measurement: MeasurementSnapshot = MeasurementSnapshot(emptyList(), null),
    val palette: PaletteId = PaletteId.IRONBOW,
    val isr: IsrScale = IsrScale.X2,
    val fps: Int = 0,
    val errorMessage: String? = null,
    val measureEdit: Boolean = false,
    val showCenter: Boolean = true,
    val showMinMax: Boolean = true,
    val mirror: Boolean = false,
    val useFahrenheit: Boolean = false,
    val samplePreview: Boolean = false,
    val denoise: Boolean = false,
    val shutterMaxSeconds: Int = 30,
    val colorBarMin: Float = 0f,
    val colorBarMax: Float = 40f,
    val userPointCount: Int = 0,
)

class ThermalEngine(
    context: android.content.Context,
    private val settings: AppSettings,
) {
    val host = UsbHostController(context)
    val measurement = MeasurementModel()

    private val uvc = UVCCamera(Tiny1BFormat.UVC_WIDTH, Tiny1BFormat.UVC_HEIGHT)
    private val running = AtomicBoolean(false)
    private val latestFrame = AtomicReference<ByteArray?>()
    private val frameLock = Object()
    private var worker: Thread? = null
    private var sampleThread: Thread? = null
    private var previewing = false
    private var frames = 0
    private var fpsWindowStart = 0L
    private var displayedFps = 0

    private val _state = MutableStateFlow(readSettings(EngineState()))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val frameCallback = IFrameCallback { frame ->
        if (frame != null && frame.size >= Tiny1BFormat.UVC_FRAME_BYTES) {
            latestFrame.set(frame.copyOf())
            synchronized(frameLock) { frameLock.notify() }
        }
    }

    init {
        measurement.showCenter = settings.showCenter
        measurement.showMinMax = settings.showMinMax
        host.onPermissionResult = { granted ->
            if (granted) connectIfPresent() else {
                _state.update {
                    it.copy(status = DeviceStatus.PermissionDenied, statusDetail = "已拒绝 USB 权限")
                }
            }
        }
        host.onAttach = { connectIfPresent() }
        host.onDetach = {
            stopPreview()
            _state.update {
                it.copy(
                    status = DeviceStatus.Searching,
                    statusDetail = "模组已断开",
                    bitmap = null,
                )
            }
            if (settings.samplePreview) startSample()
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        host.register()
        startWorker()
        if (!UVCCamera.areLibrariesLoaded()) {
            _state.update {
                it.copy(
                    status = DeviceStatus.JniUnavailable,
                    errorMessage = "当前系统无法加载 Tiny1-B 原生库，请使用 ARM64 真机。",
                )
            }
            if (settings.samplePreview) startSample()
            return
        }
        connectIfPresent()
        if (_state.value.status != DeviceStatus.Live && settings.samplePreview) {
            startSample()
        }
    }

    fun stop() {
        running.set(false)
        stopPreview()
        stopSample()
        host.unregister()
        host.close()
        synchronized(frameLock) { frameLock.notifyAll() }
        worker?.join(500)
        worker = null
    }

    fun retryConnect() = connectIfPresent()

    fun requestUsbPermission() {
        val device = host.findTiny1B() ?: return
        host.requestPermission(device)
        _state.update { it.copy(status = DeviceStatus.PermissionNeeded, statusDetail = "等待 USB 授权") }
    }

    fun shutter() {
        host.commands()?.manualShutter()
    }

    fun setKbCalibrate(enabled: Boolean) {
        host.commands()?.setKbCalibrate(enabled)
    }

    fun applyShutterMax(seconds: Int) {
        settings.shutterMaxSeconds = seconds
        host.commands()?.setShutterMaxTime(seconds)
        _state.update { it.copy(shutterMaxSeconds = seconds) }
    }

    fun setPalette(id: PaletteId) {
        settings.paletteId = id
        _state.update { it.copy(palette = id) }
    }

    fun setIsr(scale: IsrScale) {
        settings.isrScale = scale
        _state.update { it.copy(isr = scale) }
    }

    fun setShowCenter(value: Boolean) {
        settings.showCenter = value
        measurement.showCenter = value
        _state.update { it.copy(showCenter = value) }
    }

    fun setShowMinMax(value: Boolean) {
        settings.showMinMax = value
        measurement.showMinMax = value
        _state.update { it.copy(showMinMax = value) }
    }

    fun setMirror(value: Boolean) {
        settings.mirror = value
        _state.update { it.copy(mirror = value) }
    }

    fun setFahrenheit(value: Boolean) {
        settings.useFahrenheit = value
        _state.update { it.copy(useFahrenheit = value) }
    }

    fun setSamplePreview(value: Boolean) {
        settings.samplePreview = value
        _state.update { it.copy(samplePreview = value) }
        if (value && _state.value.status != DeviceStatus.Live) startSample()
        if (!value) stopSample()
    }

    fun setDenoise(value: Boolean) {
        settings.denoise = value
        _state.update { it.copy(denoise = value) }
    }

    fun setMeasureEdit(value: Boolean) {
        _state.update { it.copy(measureEdit = value) }
    }

    fun addOrSelectPoint(nx: Float, ny: Float) {
        val existing = measurement.nearestUser(nx, ny)
        if (existing != null) {
            measurement.select(existing)
        } else {
            measurement.addUser(nx, ny)
        }
        refreshMeasurementOnly()
    }

    fun moveSelected(nx: Float, ny: Float) {
        val selected = _state.value.measurement.points.firstOrNull { it.selected }?.id ?: return
        measurement.move(selected, nx, ny)
        refreshMeasurementOnly()
    }

    fun moveUser(id: Long, nx: Float, ny: Float) {
        measurement.move(id, nx, ny)
        refreshMeasurementOnly()
    }

    fun removeNearest(nx: Float, ny: Float) {
        val id = measurement.nearestUser(nx, ny, 0.07f) ?: return
        measurement.remove(id)
        refreshMeasurementOnly()
    }

    fun removeSelected() {
        val selected = _state.value.measurement.points.firstOrNull { it.selected }?.id ?: return
        measurement.remove(selected)
        refreshMeasurementOnly()
    }

    fun clearUserPoints() {
        measurement.clearUsers()
        refreshMeasurementOnly()
    }

    fun beginDrag(nx: Float, ny: Float): Long? {
        val id = measurement.nearestUser(nx, ny, 0.06f)
        if (id != null) measurement.select(id)
        return id
    }

    private fun refreshMeasurementOnly() {
        val planes = lastPlanes ?: return
        val snap = measurement.snapshot(planes)
        _state.update {
            it.copy(
                measurement = snap,
                userPointCount = snap.points.count { p -> p.kind == PointKind.USER },
            )
        }
    }

    @Volatile private var lastPlanes: ThermalPlanes? = null
    private val sampleRunning = AtomicBoolean(false)

    private fun connectIfPresent() {
        stopSample()
        val device = host.findTiny1B()
        if (device == null) {
            _state.update {
                it.copy(
                    status = if (settings.samplePreview) it.status else DeviceStatus.Searching,
                    statusDetail = "未检测到 Tiny1-B",
                )
            }
            if (settings.samplePreview) startSample()
            return
        }
        if (!host.hasPermission(device)) {
            _state.update { it.copy(status = DeviceStatus.PermissionNeeded, statusDetail = "需要 USB 权限") }
            host.requestPermission(device)
            return
        }
        stopPreview()
        _state.update { it.copy(status = DeviceStatus.Connecting, statusDetail = "正在打开模组…") }
        if (!host.open(device)) {
            _state.update { it.copy(status = DeviceStatus.Error, errorMessage = "无法打开 USB 设备") }
            return
        }
        try {
            uvc.create()
            if (!uvc.connect(host)) {
                _state.update { it.copy(status = DeviceStatus.Error, errorMessage = "UVC 连接失败") }
                return
            }
            uvc.setFrameCallback(frameCallback)
            uvc.startPreview()
            previewing = true
            host.commands()?.setShutterMaxTime(settings.shutterMaxSeconds)
            _state.update {
                it.copy(
                    status = DeviceStatus.Live,
                    statusDetail = device.deviceName,
                    errorMessage = null,
                )
            }
        } catch (error: Throwable) {
            _state.update {
                it.copy(status = DeviceStatus.Error, errorMessage = error.message ?: "连接失败")
            }
        }
    }

    private fun stopPreview() {
        if (!previewing) return
        previewing = false
        runCatching {
            uvc.stopPreview()
            uvc.release()
        }
        host.close()
    }

    private fun startWorker() {
        worker = thread(name = "tiny1b-isp", isDaemon = true) {
            while (running.get()) {
                val frame = latestFrame.getAndSet(null)
                if (frame == null) {
                    synchronized(frameLock) {
                        if (latestFrame.get() == null) {
                            runCatching { frameLock.wait(200) }
                        }
                    }
                    continue
                }
                processFrame(frame)
            }
        }
    }

    private fun startSample() {
        if (!sampleRunning.compareAndSet(false, true)) return
        sampleThread = thread(name = "tiny1b-sample", isDaemon = true) {
            var t = 0f
            _state.update { it.copy(status = DeviceStatus.Sample, statusDetail = "样例画面（无模组）") }
            try {
                while (running.get() && sampleRunning.get() && settings.samplePreview && _state.value.status != DeviceStatus.Live) {
                    processFrame(SyntheticScene.uvcFrame(t = t))
                    t += 0.07f
                    Thread.sleep(45)
                }
            } finally {
                sampleRunning.set(false)
            }
        }
    }

    private fun stopSample() {
        sampleRunning.set(false)
        sampleThread = null
    }

    private fun processFrame(frame: ByteArray) {
        var planes = runCatching { FrameParser.parseUvcFrame(frame) }.getOrNull() ?: return
        if (settings.mirror) {
            planes = FrameParser.mirrorHorizontal(planes)
        }
        lastPlanes = planes
        val palette = Palettes.get(settings.paletteId)
        val rendered: RenderedFrame = SuperResolution.enhance(
            planes,
            settings.isrScale,
            palette,
            denoise = settings.denoise,
        )
        val snap = measurement.snapshot(planes)
        val bmp = Bitmap.createBitmap(rendered.width, rendered.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(rendered.argb, 0, rendered.width, 0, 0, rendered.width, rendered.height)
        frames++
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1000L) {
            displayedFps = frames
            frames = 0
            fpsWindowStart = now
        }
        val minC = snap.stats?.min?.celsius ?: 0f
        val maxC = snap.stats?.max?.celsius ?: 0f
        _state.update {
            it.copy(
                bitmap = bmp,
                measurement = snap,
                fps = displayedFps,
                palette = settings.paletteId,
                isr = settings.isrScale,
                colorBarMin = minC,
                colorBarMax = maxC,
                userPointCount = snap.points.count { p -> p.kind == PointKind.USER },
            )
        }
    }

    private fun readSettings(base: EngineState): EngineState = base.copy(
        palette = settings.paletteId,
        isr = settings.isrScale,
        showCenter = settings.showCenter,
        showMinMax = settings.showMinMax,
        mirror = settings.mirror,
        useFahrenheit = settings.useFahrenheit,
        samplePreview = settings.samplePreview,
        denoise = settings.denoise,
        shutterMaxSeconds = settings.shutterMaxSeconds,
    )
}
