package com.pipidu.tiny1b.device

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
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
import com.pipidu.tiny1b.data.AppSettings
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

    private val capture = UvcCapture()
    private val running = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val activityResumed = AtomicBoolean(false)
    private val permissionRequestInFlight = AtomicBoolean(false)
    private val pausedDuringPermissionRequest = AtomicBoolean(false)
    private val oneShotPermissionUsed = AtomicBoolean(false)
    private val listPermissionAfterAttachUsed = AtomicBoolean(false)
    @Volatile private var attachGrantedDevice: android.hardware.usb.UsbDevice? = null
    private val latestFrame = AtomicReference<ByteArray?>()
    private val frameLock = Object()
    private val connectLock = Any()
    private var worker: Thread? = null
    private var sampleThread: Thread? = null
    @Volatile private var previewing = false
    private var frames = 0
    private var fpsWindowStart = 0L
    private var displayedFps = 0

    private val _state = MutableStateFlow(readSettings(EngineState()))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    init {
        measurement.showCenter = settings.showCenter
        measurement.showMinMax = settings.showMinMax
        host.onAttach = { connectIfPresent(allowPermissionRequest = false) }
        host.onDetach = {
            attachGrantedDevice = null
            oneShotPermissionUsed.set(false)
            listPermissionAfterAttachUsed.set(false)
            permissionRequestInFlight.set(false)
            synchronized(connectLock) { disconnectLocked() }
            _state.update {
                it.copy(
                    status = DeviceStatus.Searching,
                    statusDetail = "模组已断开",
                    bitmap = null,
                    errorMessage = null,
                )
            }
            if (settings.samplePreview) startSample()
        }
        host.onPermissionResult = { granted, hasGrantExtra -> onUsbPermissionResult(granted, hasGrantExtra) }
    }

    fun start() {
        if (running.getAndSet(true)) {
            host.register()
            return
        }
        host.register()
        startWorker()
        if (settings.samplePreview) startSample()
    }

    fun stop() {
        running.set(false)
        synchronized(connectLock) { disconnectLocked() }
        stopSample()
        host.unregister()
        synchronized(frameLock) { frameLock.notifyAll() }
        worker?.join(500)
        worker = null
    }

    fun retryConnect() = connectIfPresent(allowPermissionRequest = false)

    fun bindActivity(activity: android.app.Activity) {
        host.bindActivity(activity)
    }

    fun unbindActivity(activity: android.app.Activity) {
        host.unbindActivity(activity)
    }

    /**
     * System USB attach intent already granted access. Open without
     * calling UsbManager.requestPermission.
     * @return true if this was a Tiny1-B attach intent.
     */
    fun onLaunchIntent(intent: android.content.Intent?): Boolean {
        val device = host.tiny1bFromAttachIntent(intent) ?: return false
        Log.i(TAG, "USB_DEVICE_ATTACHED intent device=${device.deviceName}")
        attachGrantedDevice = device
        host.clearDenied()
        oneShotPermissionUsed.set(true)
        permissionRequestInFlight.set(false)
        pausedDuringPermissionRequest.set(false)
        if (running.get()) connectIfPresent(allowPermissionRequest = false)
        return true
    }

    fun onActivityResumed() {
        activityResumed.set(true)
        if (running.get()) {
            connectIfPresent(allowPermissionRequest = !oneShotPermissionUsed.get())
        }
    }

    fun onActivityPaused() {
        if (permissionRequestInFlight.get()) {
            pausedDuringPermissionRequest.set(true)
        }
        activityResumed.set(false)
    }

    /**
     * Instant granted=false with no EXTRA is not a user refuse — in-app
     * requestPermission never showed a dialog. Tell the user to unplug/replug
     * so the Activity attach intent can grant (demo path).
     */
    fun onUsbPermissionResult(granted: Boolean, hasGrantExtra: Boolean) {
        permissionRequestInFlight.set(false)
        oneShotPermissionUsed.set(true)
        if (granted) {
            host.clearDenied()
            pausedDuringPermissionRequest.set(false)
            connectIfPresent(allowPermissionRequest = false)
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - host.lastPermissionRequestAt
        val looksLikeUserDialog = hasGrantExtra && (pausedDuringPermissionRequest.get() || elapsed >= 800)
        pausedDuringPermissionRequest.set(false)
        if (!looksLikeUserDialog) {
            Log.w(TAG, "in-app USB request produced no dialog (${elapsed}ms extra=$hasGrantExtra)")
            showReplugCard()
            return
        }
        host.markDenied()
        _state.update {
            it.copy(
                status = DeviceStatus.PermissionDenied,
                statusDetail = "已拒绝 USB 权限",
                errorMessage = "你拒绝了 USB 访问。请拔掉 Tiny1-B 再插入，并在系统弹窗中选择「允许」。",
            )
        }
    }

    fun onUncaught(thread: Thread, error: Throwable) {
        Log.e(TAG, "uncaught on ${thread.name}", error)
        if (_state.value.status == DeviceStatus.Connecting || _state.value.status == DeviceStatus.Live) {
            failConnect("连接过程异常：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun promptUsbPermissionOnce(device: android.hardware.usb.UsbDevice) {
        if (!activityResumed.get()) {
            showReplugCard()
            return
        }
        permissionRequestInFlight.set(true)
        pausedDuringPermissionRequest.set(false)
        val error = host.requestPermission(device)
        if (error != null) {
            permissionRequestInFlight.set(false)
            oneShotPermissionUsed.set(true)
            showReplugCard(error)
            return
        }
        _state.update {
            it.copy(
                status = DeviceStatus.PermissionNeeded,
                statusDetail = "等待 USB 授权",
                errorMessage = "若出现系统窗口请选择「允许」。若没有弹窗，请拔掉 Tiny1-B 再插入。",
            )
        }
    }

    private fun showReplugCard(detail: String? = null) {
        _state.update {
            it.copy(
                status = DeviceStatus.PermissionNeeded,
                statusDetail = "请重新插入模组",
                errorMessage = detail ?: "插入 Tiny1-B 时，系统会询问是否允许本应用访问 USB，请选择「允许」。应用内按钮无法代替这次系统授权；请拔掉再插入。",
            )
        }
    }

    fun shutter() {
        runCatching { host.commands()?.manualShutter() }
    }

    fun setKbCalibrate(enabled: Boolean) {
        runCatching { host.commands()?.setKbCalibrate(enabled) }
    }

    fun applyShutterMax(seconds: Int) {
        settings.shutterMaxSeconds = seconds
        runCatching { host.commands()?.setShutterMaxTime(seconds) }
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

    private fun connectIfPresent(allowPermissionRequest: Boolean = true) {
        if (!running.get()) return
        stopSample()
        val attached = attachGrantedDevice
        val live = host.liveTiny1B(attached)
        val device = live ?: attached
        if (device == null) {
            _state.update {
                it.copy(
                    status = if (settings.samplePreview) it.status else DeviceStatus.Searching,
                    statusDetail = "未检测到 Tiny1-B",
                    errorMessage = if (settings.samplePreview) it.errorMessage else null,
                )
            }
            if (settings.samplePreview) startSample()
            return
        }
        val grantedByAttach = attached != null
        if (!grantedByAttach && !host.hasPermission(live ?: device)) {
            if (allowPermissionRequest && !oneShotPermissionUsed.getAndSet(true) && !permissionRequestInFlight.get()) {
                promptUsbPermissionOnce(live ?: device)
            } else {
                showReplugCard()
            }
            return
        }
        synchronized(connectLock) {
            if (previewing && _state.value.status == DeviceStatus.Live && host.isOpen()) {
                return
            }
        }
        if (!connecting.compareAndSet(false, true)) return
        _state.update {
            it.copy(status = DeviceStatus.Connecting, statusDetail = "正在打开模组…", errorMessage = null)
        }
        thread(name = "tiny1b-connect", isDaemon = true) {
            try {
                synchronized(connectLock) {
                    openAndStartLocked(preferred = attached ?: device, grantedByAttachIntent = grantedByAttach)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "connect", error)
                failConnect("连接失败：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun openAndStartLocked(
        preferred: android.hardware.usb.UsbDevice?,
        grantedByAttachIntent: Boolean,
    ) {
        if (!running.get()) return
        if (previewing && _state.value.status == DeviceStatus.Live && host.isOpen()) return
        disconnectLocked()
        val opened = host.open(preferred, grantedByAttachIntent = grantedByAttachIntent)
        if (!opened.ok) {
            val live = host.liveTiny1B(preferred)
            if (opened.needsListPermission &&
                live != null &&
                activityResumed.get() &&
                !listPermissionAfterAttachUsed.getAndSet(true)
            ) {
                Log.i(TAG, "attach extra/list open failed; requestPermission on deviceList ${host.describe(live)}")
                promptUsbPermissionOnce(live)
                return
            }
            val prefix = if (grantedByAttachIntent) {
                "系统已允许 USB，但打开 Intent 设备对象失败。"
            } else {
                "无法打开 USB 设备。"
            }
            failConnectLocked("$prefix ${opened.message ?: "未知错误"}".trim())
            return
        }
        val conn = host.connection()
        val device = host.openedDevice()
        if (conn == null || device == null) {
            failConnectLocked("USB 连接为空。请重新插拔模组后再试。${opened.message ?: ""}")
            return
        }
        val error = capture.start(
            connection = conn,
            device = device,
            onFrame = { frame ->
                latestFrame.set(frame)
                synchronized(frameLock) { frameLock.notify() }
            },
            onError = { message -> failConnect(message) },
        )
        if (error != null) {
            failConnectLocked(error)
            return
        }
        previewing = true
        runCatching { host.commands()?.setShutterMaxTime(settings.shutterMaxSeconds) }
        _state.update {
            it.copy(
                status = DeviceStatus.Live,
                statusDetail = device.deviceName,
                errorMessage = null,
            )
        }
    }

    private fun failConnect(message: String) {
        synchronized(connectLock) { failConnectLocked(message) }
    }

    private fun failConnectLocked(message: String) {
        disconnectLocked()
        _state.update {
            it.copy(
                status = DeviceStatus.Error,
                statusDetail = "连接失败",
                errorMessage = message,
                bitmap = null,
            )
        }
    }

    private fun disconnectLocked() {
        previewing = false
        runCatching { capture.stop() }
        host.close()
    }

    private fun startWorker() {
        if (worker?.isAlive == true) return
        worker = thread(name = "tiny1b-isp", isDaemon = true) {
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
                onUncaught(t, e)
            }
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
                try {
                    processFrame(frame)
                } catch (error: Throwable) {
                    Log.e(TAG, "processFrame", error)
                }
            }
        }
    }

    private fun startSample() {
        if (!sampleRunning.compareAndSet(false, true)) return
        sampleThread = thread(name = "tiny1b-sample", isDaemon = true) {
            var t = 0f
            _state.update {
                it.copy(
                    status = DeviceStatus.Sample,
                    statusDetail = "样例画面（无模组）",
                    errorMessage = null,
                )
            }
            try {
                while (running.get() && sampleRunning.get() && settings.samplePreview && _state.value.status != DeviceStatus.Live) {
                    runCatching { processFrame(SyntheticScene.uvcFrame(t = t)) }
                    t += 0.07f
                    Thread.sleep(45)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "sample", error)
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

    companion object {
        private const val TAG = "ThermalEngine"
    }
}
