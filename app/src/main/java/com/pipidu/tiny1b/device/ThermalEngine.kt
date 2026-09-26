package com.pipidu.tiny1b.device

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.WindowManager
import com.pipidu.tiny1b.capture.CaptureStore
import com.pipidu.tiny1b.capture.ThermalRecorder
import com.pipidu.tiny1b.core.DisplayRotation
import com.pipidu.tiny1b.core.FrameGenScale
import com.pipidu.tiny1b.core.FrameGeneration
import com.pipidu.tiny1b.core.FrameParser
import com.pipidu.tiny1b.core.IspScratch
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.core.MeasurementModel
import com.pipidu.tiny1b.core.MeasurementSnapshot
import com.pipidu.tiny1b.core.PaletteId
import com.pipidu.tiny1b.core.Palettes
import com.pipidu.tiny1b.core.PointKind
import com.pipidu.tiny1b.core.SuperResolution
import com.pipidu.tiny1b.core.SyntheticScene
import com.pipidu.tiny1b.core.ThermalPlanes
import com.pipidu.tiny1b.core.Tiny1BFormat
import com.pipidu.tiny1b.data.AppCache
import com.pipidu.tiny1b.data.AppSettings
import com.zz.infisense.camera.IFrameCallback
import com.zz.infisense.camera.UVCCamera
import com.zz.infisense.camera.UsbControlBlock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DeviceStatus {
    Searching,
    RequestingPermission,
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
    val useDownloadMirror: Boolean = true,
    val denoiseAmount: Int = 0,
    val rotation: DisplayRotation = DisplayRotation.DEG_0,
    val frameGen: FrameGenScale = FrameGenScale.OFF,
    val markerOpacity: Int = 100,
    val sharpenAmount: Int = 0,
    val spanFixed: Boolean = false,
    val spanLowC: Float = 0f,
    val spanHighC: Float = 40f,
    val shutterMaxSeconds: Int = 30,
    val colorBarMin: Float = 0f,
    val colorBarMax: Float = 40f,
    val userPointCount: Int = 0,
    val recording: Boolean = false,
    val captureHint: String? = null,
)

/**
 * Tiny1-B live engine. USB open/preview is the vendor demo path
 * ([UVCCamera] / [UsbControlBlock] / libUVCCamera), not the 1.0.5–1.0.9
 * Kotlin UsbManager UVC stack.
 */
class ThermalEngine(
    context: android.content.Context,
    private val settings: AppSettings,
) {
    val measurement = MeasurementModel()

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val running = AtomicBoolean(false)
    private val activityResumed = AtomicBoolean(false)
    private val latestFrame = AtomicReference<ByteArray?>()
    private val cameraLock = Any()
    private var ispThread: HandlerThread? = null
    private var ispHandler: Handler? = null
    private var sampleThread: Thread? = null
    @Volatile private var previewing = false
    @Volatile private var nativeLoadFailed = false
    private val recorder = ThermalRecorder(appContext)
    @Volatile private var recordingStartedAt = 0L
    private var frames = 0
    private var fpsWindowStart = 0L
    private var displayedFps = 0
    private var activity: Activity? = null
    private var camera: UVCCamera? = null
    private var usbReceiverRegistered = false
    private val uvcScratch = Array(UVC_SCRATCH) { ByteArray(Tiny1BFormat.UVC_FRAME_BYTES) }
    private val uvcWrite = AtomicInteger(0)
    private val uvcBusy = AtomicInteger(-1)
    private val sampleScratch = Array(2) { ByteArray(Tiny1BFormat.UVC_FRAME_BYTES) }
    private val liveBitmaps = arrayOfNulls<Bitmap>(LIVE_BITMAPS)
    private var liveBmpSlot = 0
    private val ispScratch = IspScratch()
    private var parseLum = FloatArray(Tiny1BFormat.PLANE_WIDTH * Tiny1BFormat.PLANE_HEIGHT)
    private var parseKel = IntArray(Tiny1BFormat.PLANE_WIDTH * Tiny1BFormat.PLANE_HEIGHT)
    @Volatile private var prevHold: ThermalPlanes? = null
    @Volatile private var currHold: ThermalPlanes? = null
    @Volatile private var blendHold: ThermalPlanes? = null
    @Volatile private var pacingActive = false
    @Volatile private var shownCurr = true
    @Volatile private var scheduledExtras = 0
    @Volatile private var nextBlendK = 1
    @Volatile private var pairOriginMs = 0L
    @Volatile private var pairDtMs = FrameGeneration.DEFAULT_NATIVE_MS
    private var lastNativeAt = 0L
    @Volatile private var nativeIntervalMs = FrameGeneration.DEFAULT_NATIVE_MS

    private val _state = MutableStateFlow(readSettings(EngineState()))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val frameCallback = IFrameCallback { frame ->
        if (!previewing) return@IFrameCallback
        if (frame == null || frame.size < Tiny1BFormat.UVC_FRAME_BYTES) return@IFrameCallback
        var idx = (uvcWrite.get() + 1) % UVC_SCRATCH
        val busy = uvcBusy.get()
        if (idx == busy) idx = (idx + 1) % UVC_SCRATCH
        System.arraycopy(frame, 0, uvcScratch[idx], 0, Tiny1BFormat.UVC_FRAME_BYTES)
        uvcWrite.set(idx)
        latestFrame.set(uvcScratch[idx])
        postNativeAvailable()
    }

    private val usbHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                UsbControlBlock.USB_PERMISSION -> {
                    if (msg.arg1 == UsbControlBlock.USB_PERMIT) {
                        Log.i(TAG, "USB permission granted")
                        tryOpenCamera("permission")
                    } else {
                        onPermissionDenied()
                    }
                }
                UsbControlBlock.USB_ATTACH -> {
                    val device = msg.obj as? UsbDevice
                    if (device != null && !isTiny1B(device)) return
                    tryOpenCamera("attach")
                }
                UsbControlBlock.USB_DETACH -> onUsbDetach(msg.obj as? UsbDevice)
            }
        }
    }

    private val attachRetryRunnable = Runnable {
        tryOpenCamera("retry")
    }

    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = extraUsbDevice(intent)
                    val message = usbHandler.obtainMessage(UsbControlBlock.USB_ATTACH)
                    message.obj = device
                    usbHandler.sendMessage(message)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = extraUsbDevice(intent)
                    val message = usbHandler.obtainMessage(UsbControlBlock.USB_DETACH)
                    message.obj = device
                    usbHandler.sendMessage(message)
                }
            }
        }
    }

    init {
        measurement.showCenter = settings.showCenter
        measurement.showMinMax = settings.showMinMax
    }

    fun start() {
        if (running.getAndSet(true)) return
        registerUsbReceiver()
        startWorker()
        if (settings.samplePreview) startSample()
    }

    fun stop() {
        running.set(false)
        usbHandler.removeCallbacks(attachRetryRunnable)
        usbHandler.removeCallbacks(presenceCheckRunnable)
        usbHandler.removeCallbacks(recTick)
        usbHandler.removeCallbacks(clearHintRunnable)
        unregisterUsbReceiver()
        runCatching { stopRecordingInternal(null) }
        synchronized(cameraLock) { destroyCameraLocked() }
        stopSample()
        stopWorker()
        prevHold = null
        currHold = null
        blendHold = null
        lastPlanes = null
        latestFrame.set(null)
        _state.update { it.copy(bitmap = null, measureEdit = false, recording = false) }
        recycleLiveBitmaps(except = null, forceAll = true)
    }

    fun retryConnect() {
        if (!running.get()) return
        usbHandler.removeCallbacks(attachRetryRunnable)
        usbHandler.post { tryOpenCamera("retry-button") }
    }

    fun bindActivity(activity: Activity) {
        this.activity = activity
    }

    fun unbindActivity(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null
        }
    }

    fun onLaunchIntent(intent: Intent?): Boolean {
        val attached = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        val device = intent?.let { extraUsbDevice(it) }
        if (attached && device != null && !isTiny1B(device)) return false
        if (!running.get()) return attached
        retryConnect()
        return attached
    }

    fun onActivityResumed() {
        activityResumed.set(true)
        if (!running.get()) return
        registerUsbReceiver()
        usbHandler.removeCallbacks(attachRetryRunnable)
        usbHandler.post { tryOpenCamera("resume") }
    }

    fun onActivityPaused() {
        activityResumed.set(false)
        // Keep the USB attach/detach receiver. ColorOS can pause the Activity
        // on unplug; unregistering here misses USB_DEVICE_DETACHED and freezes
        // the last frame with status still 已连接.
        // Do not destroy the JNI camera here. The USB permission dialog pauses
        // the Activity; the demo retries on resume after the grant is stored.
    }

    fun onUncaught(thread: Thread, error: Throwable) {
        Log.e(TAG, "uncaught on ${thread.name}", error)
        if (_state.value.status == DeviceStatus.Connecting || _state.value.status == DeviceStatus.Live) {
            failUi("连接过程异常：${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun shutter() {
        if (_state.value.status != DeviceStatus.Live) return
        synchronized(cameraLock) {
            runCatching { camera?.manualShut() }
        }
    }

    fun setKbCalibrate(enabled: Boolean) {
        synchronized(cameraLock) {
            runCatching {
                if (enabled) camera?.setKbCalibrateValid() else camera?.setKbCalibrateInvalid()
            }
        }
    }

    fun applyShutterMax(seconds: Int) {
        settings.shutterMaxSeconds = seconds
        synchronized(cameraLock) {
            runCatching { camera?.setShutterMaxTime(seconds.toByte()) }
        }
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

    fun setFrameGen(scale: FrameGenScale) {
        settings.frameGenScale = scale
        _state.update { it.copy(frameGen = scale) }
        if (scale == FrameGenScale.OFF) {
            val handler = ispHandler
            if (handler != null) {
                handler.post { cancelPacing(showHeldNative = true) }
            } else {
                cancelPacing(showHeldNative = false)
            }
        }
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

    fun setRotation(value: DisplayRotation) {
        val from = settings.rotation
        if (from != value) {
            measurement.remapUsers(from, value)
        }
        settings.rotation = value
        _state.update { it.copy(rotation = value) }
    }

    fun cycleRotation() {
        setRotation(settings.rotation.nextClockwise())
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

    fun setUseDownloadMirror(value: Boolean) {
        settings.useDownloadMirror = value
        _state.update { it.copy(useDownloadMirror = value) }
    }

    fun setDenoiseAmount(percent: Int) {
        val value = percent.coerceIn(0, 100)
        settings.denoiseAmount = value
        _state.update { it.copy(denoiseAmount = value) }
    }

    fun setMarkerOpacity(percent: Int) {
        val value = percent.coerceIn(0, 100)
        settings.markerOpacity = value
        _state.update { it.copy(markerOpacity = value) }
    }

    fun setSharpenAmount(percent: Int) {
        val value = percent.coerceIn(0, 100)
        settings.sharpenAmount = value
        _state.update { it.copy(sharpenAmount = value) }
    }

    fun setSpanFixed(value: Boolean) {
        if (value && !settings.spanFixed) {
            val lo = _state.value.colorBarMin
            val hi = _state.value.colorBarMax
            if (hi - lo >= AppSettings.SPAN_MIN_GAP_C) {
                settings.spanLowC = lo.coerceIn(AppSettings.SPAN_MIN_C, AppSettings.SPAN_MAX_C)
                settings.spanHighC = hi.coerceIn(AppSettings.SPAN_MIN_C, AppSettings.SPAN_MAX_C)
            }
        }
        settings.spanFixed = value
        _state.update {
            it.copy(
                spanFixed = value,
                spanLowC = settings.spanLowC,
                spanHighC = settings.spanHighC,
                colorBarMin = if (value) settings.spanLowC else it.colorBarMin,
                colorBarMax = if (value) settings.spanHighC else it.colorBarMax,
            )
        }
    }

    fun setSpanLowC(celsius: Float) {
        val lo = celsius.coerceIn(AppSettings.SPAN_MIN_C, settings.spanHighC - AppSettings.SPAN_MIN_GAP_C)
        settings.spanLowC = lo
        _state.update {
            it.copy(
                spanLowC = lo,
                colorBarMin = if (settings.spanFixed) lo else it.colorBarMin,
            )
        }
    }

    fun setSpanHighC(celsius: Float) {
        val hi = celsius.coerceIn(settings.spanLowC + AppSettings.SPAN_MIN_GAP_C, AppSettings.SPAN_MAX_C)
        settings.spanHighC = hi
        _state.update {
            it.copy(
                spanHighC = hi,
                colorBarMax = if (settings.spanFixed) hi else it.colorBarMax,
            )
        }
    }

    fun setMeasureEdit(value: Boolean) {
        if (value && _state.value.status != DeviceStatus.Live) return
        _state.update { it.copy(measureEdit = value) }
    }

    fun addOrSelectPoint(nx: Float, ny: Float) {
        if (!_state.value.measureEdit) return
        val existing = measurement.nearestUser(nx, ny)
        if (existing != null) {
            measurement.select(existing)
        } else {
            measurement.addUser(nx, ny)
        }
        refreshMeasurementOnly()
    }

    fun moveSelected(nx: Float, ny: Float) {
        if (!_state.value.measureEdit) return
        val selected = _state.value.measurement.points.firstOrNull { it.selected }?.id ?: return
        measurement.move(selected, nx, ny)
        refreshMeasurementOnly()
    }

    fun moveUser(id: Long, nx: Float, ny: Float) {
        if (!_state.value.measureEdit) return
        measurement.move(id, nx, ny)
        refreshMeasurementOnly()
    }

    fun removeNearest(nx: Float, ny: Float) {
        if (!_state.value.measureEdit) return
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
        if (!_state.value.measureEdit) return null
        val id = measurement.nearestUser(nx, ny, 0.06f)
        if (id != null) measurement.select(id)
        return id
    }

    fun capturePhoto() {
        val src = _state.value.bitmap
        if (src == null || src.isRecycled) {
            flashCaptureHint("没有可保存的画面")
            return
        }
        val copy = src.copy(Bitmap.Config.ARGB_8888, false) ?: run {
            flashCaptureHint("无法复制当前画面")
            return
        }
        thread(name = "tiny1b-photo", isDaemon = true) {
            val message = runCatching {
                CaptureStore.saveJpeg(appContext, copy)
                "照片已保存到相册"
            }.getOrElse { error ->
                "保存照片失败：${error.message ?: error.javaClass.simpleName}"
            }
            runCatching { copy.recycle() }
            usbHandler.post { flashCaptureHint(message) }
        }
    }

    fun toggleRecord() {
        if (recorder.recording) {
            stopRecordingInternal(null)
            return
        }
        val bmp = _state.value.bitmap
        if (bmp == null || bmp.isRecycled) {
            flashCaptureHint("没有可录像的画面")
            return
        }
        val started = runCatching { recorder.start(bmp.width, bmp.height) }
        if (started.isFailure) {
            val error = started.exceptionOrNull()
            flashCaptureHint("无法开始录像：${error?.message ?: error?.javaClass?.simpleName}")
            return
        }
        recordingStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(recording = true, captureHint = "录像中 00:00") }
        usbHandler.removeCallbacks(clearHintRunnable)
        usbHandler.removeCallbacks(recTick)
        usbHandler.post(recTick)
    }

    fun flashCaptureHint(message: String) {
        _state.update { it.copy(captureHint = message) }
        usbHandler.removeCallbacks(clearHintRunnable)
        if (!recorder.recording) {
            usbHandler.postDelayed(clearHintRunnable, HINT_MS)
        }
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

    private fun tryOpenCamera(reason: String) {
        if (!running.get() || nativeLoadFailed) return
        if (previewing && camera?.openStatus == true) {
            if (findModule() == null) {
                onUsbDetach(null)
            }
            return
        }
        synchronized(cameraLock) {
            if (previewing && camera?.openStatus == true) {
                return
            }
            val act = activity
            if (act == null) {
                showKeepForegroundCard()
                scheduleRetry()
                return
            }
            if (!ensureCameraLocked(act)) {
                scheduleRetry()
                return
            }
            val cam = camera ?: return
            val module = findModule()
            if (module == null) {
                if (previewing) {
                    abandonCameraLocked()
                }
                showSearching()
                scheduleRetry()
                return
            }
            stopSample()
            if (!usbManager.hasPermission(module) && !activityResumed.get()) {
                showKeepForegroundCard()
                scheduleRetry()
                return
            }
            if (!usbManager.hasPermission(module)) {
                showRequestingCard()
            } else {
                _state.update {
                    it.copy(
                        status = DeviceStatus.Connecting,
                        statusDetail = "正在打开模组…",
                        errorMessage = null,
                    )
                }
            }
            val opened = runCatching { cam.open() }.getOrElse { error ->
                Log.e(TAG, "UVCCamera.open ($reason)", error)
                destroyCameraLocked()
                failUi("打开相机失败：${error.javaClass.simpleName}: ${error.message}")
                scheduleRetry()
                return
            }
            if (!opened) {
                if (!usbManager.hasPermission(module)) {
                    showRequestingCard()
                } else {
                    _state.update {
                        it.copy(
                            status = DeviceStatus.Connecting,
                            statusDetail = "正在打开模组…",
                            errorMessage = "已授权 USB，正在连接 libUVCCamera…",
                        )
                    }
                }
                scheduleRetry()
                return
            }
            runCatching {
                cam.setOpenStatus(true)
                cam.setFrameCallback(frameCallback)
                cam.startPreview()
                cam.setShutterMaxTime(settings.shutterMaxSeconds.toByte())
            }.onFailure { error ->
                Log.e(TAG, "startPreview", error)
                destroyCameraLocked()
                failUi("无法开始预览：${error.message}")
                scheduleRetry()
                return
            }
            previewing = true
            usbHandler.removeCallbacks(attachRetryRunnable)
            usbHandler.removeCallbacks(presenceCheckRunnable)
            usbHandler.post(presenceCheckRunnable)
            _state.update {
                it.copy(
                    status = DeviceStatus.Live,
                    statusDetail = cam.deviceName ?: module.deviceName,
                    errorMessage = null,
                )
            }
            Log.i(TAG, "live via UVCCamera ($reason) ${cam.deviceName}")
        }
    }

    private fun onPermissionDenied() {
        _state.update {
            it.copy(
                status = DeviceStatus.PermissionDenied,
                statusDetail = "已拒绝 USB 权限",
                errorMessage = "你拒绝了 USB 访问。请点「重新扫描」，并在系统弹窗中选择「允许」。",
            )
        }
        scheduleRetry()
    }

    private fun onUsbDetach(device: UsbDevice?) {
        if (device != null && !isTiny1B(device)) return
        // Compose/engine state first so the waiting-connect UI is on screen
        // even if native teardown is slow. Never leave the last frame up.
        enterWaitingUi("模组已断开。请重新插入 Tiny1-B。")
        synchronized(cameraLock) {
            abandonCameraLocked()
        }
        usbHandler.removeCallbacks(attachRetryRunnable)
        usbHandler.postDelayed(attachRetryRunnable, RETRY_MS)
    }

    private fun enterWaitingUi(detail: String) {
        previewing = false
        latestFrame.set(null)
        lastPlanes = null
        prevHold = null
        cancelPacing(showHeldNative = false)
        usbHandler.removeCallbacks(presenceCheckRunnable)
        runCatching { stopRecordingInternal("模组已断开，录像已停止") }
        _state.update {
            it.copy(
                status = DeviceStatus.Searching,
                statusDetail = "模组已断开",
                bitmap = null,
                errorMessage = detail,
                recording = false,
                measureEdit = false,
            )
        }
    }

    private fun ensureCameraLocked(activity: Activity): Boolean {
        if (camera != null) return true
        return try {
            val cam = UVCCamera(
                Tiny1BFormat.VENDOR_ID,
                Tiny1BFormat.PRODUCT_ID,
                Tiny1BFormat.UVC_WIDTH,
                Tiny1BFormat.UVC_HEIGHT,
                activity,
                usbHandler,
            )
            cam.create()
            camera = cam
            true
        } catch (error: Throwable) {
            nativeLoadFailed = error is UnsatisfiedLinkError
            Log.e(TAG, "UVCCamera.create", error)
            failUi("无法加载 USB 相机库：${error.message}")
            false
        }
    }

    private fun destroyCameraLocked() {
        previewing = false
        val cam = camera
        camera = null
        if (cam == null) return
        runCatching { cam.setFrameCallback(null) }
        runCatching { cam.stopPreview() }
        runCatching { cam.setOpenStatus(false) }
        runCatching { cam.destroy() }
    }

    private fun abandonCameraLocked() {
        previewing = false
        val cam = camera
        camera = null
        if (cam == null) return
        runCatching { cam.abandon() }
    }

    private fun stopPreviewLocked() {
        previewing = false
        val cam = camera ?: return
        runCatching { cam.setFrameCallback(null) }
        runCatching { cam.stopPreview() }
        runCatching { cam.setOpenStatus(false) }
    }

    private fun scheduleRetry() {
        usbHandler.removeCallbacks(attachRetryRunnable)
        usbHandler.postDelayed(attachRetryRunnable, RETRY_MS)
    }

    private fun findModule(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull(::isTiny1B)

    private fun isTiny1B(device: UsbDevice): Boolean =
        device.vendorId == Tiny1BFormat.VENDOR_ID && device.productId == Tiny1BFormat.PRODUCT_ID

    private fun extraUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun registerUsbReceiver() {
        if (usbReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(usbStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(usbStateReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    private fun unregisterUsbReceiver() {
        if (!usbReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(usbStateReceiver) }
        usbReceiverRegistered = false
    }

    private fun showRequestingCard(detail: String? = null) {
        _state.update {
            it.copy(
                status = DeviceStatus.RequestingPermission,
                statusDetail = "正在请求 USB 权限",
                errorMessage = detail ?: "请在系统弹窗中选择「允许」。",
            )
        }
    }

    private fun showKeepForegroundCard(detail: String? = null) {
        _state.update {
            it.copy(
                status = DeviceStatus.PermissionNeeded,
                statusDetail = "请保持应用在前台",
                errorMessage = detail ?: "Tiny1-B 已连接，但尚未获得 USB 权限。请将应用保持在前台，系统会请求授权。",
            )
        }
    }

    private fun showSearching() {
        _state.update {
            it.copy(
                status = DeviceStatus.Searching,
                statusDetail = "未检测到 Tiny1-B",
                bitmap = null,
                errorMessage = null,
                measureEdit = false,
            )
        }
    }

    private fun failUi(message: String) {
        _state.update {
            it.copy(
                status = DeviceStatus.Error,
                statusDetail = "连接失败",
                errorMessage = message,
                bitmap = null,
                measureEdit = false,
            )
        }
    }

    private fun startWorker() {
        if (ispThread?.isAlive == true) return
        val thread = HandlerThread("tiny1b-isp")
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
            onUncaught(t, e)
        }
        thread.start()
        ispThread = thread
        ispHandler = object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_NATIVE -> {
                        val frame = latestFrame.getAndSet(null) ?: return
                        val busy = uvcScratch.indexOfFirst { it === frame }
                        if (busy >= 0) uvcBusy.set(busy)
                        try {
                            ingestNativeFrame(frame)
                        } catch (error: Throwable) {
                            Log.e(TAG, "processFrame", error)
                        } finally {
                            uvcBusy.set(-1)
                        }
                    }
                    MSG_VSYNC -> {
                        if (latestFrame.get() != null) return
                        try {
                            emitDuePacedFrame()
                        } catch (error: Throwable) {
                            Log.e(TAG, "frameGen", error)
                        }
                    }
                }
            }
        }
    }

    private fun stopWorker() {
        cancelPacing(showHeldNative = false)
        val handler = ispHandler
        ispHandler = null
        handler?.removeCallbacksAndMessages(null)
        val thread = ispThread
        ispThread = null
        thread?.quitSafely()
        thread?.join(500)
    }

    private fun postNativeAvailable() {
        val handler = ispHandler ?: return
        if (!handler.hasMessages(MSG_NATIVE)) {
            handler.sendEmptyMessage(MSG_NATIVE)
        }
    }

    private val vsyncCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!pacingActive) return
            val handler = ispHandler
            if (handler != null && !handler.hasMessages(MSG_VSYNC)) {
                handler.sendEmptyMessage(MSG_VSYNC)
            }
            if (pacingActive) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun startPacing() {
        if (pacingActive) return
        pacingActive = true
        usbHandler.post {
            if (pacingActive) {
                Choreographer.getInstance().postFrameCallback(vsyncCallback)
            }
        }
    }

    private fun stopPacing() {
        pacingActive = false
        usbHandler.post {
            Choreographer.getInstance().removeFrameCallback(vsyncCallback)
        }
        ispHandler?.removeMessages(MSG_VSYNC)
    }

    private fun cancelPacing(showHeldNative: Boolean) {
        stopPacing()
        scheduledExtras = 0
        nextBlendK = 1
        val curr = currHold
        if (showHeldNative && curr != null && !shownCurr) {
            shownCurr = true
            processPlanes(curr)
        } else {
            shownCurr = true
        }
    }

    private fun displayRefreshHz(): Float {
        val hz = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                (activity?.display ?: appContext.display)?.refreshRate
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(WindowManager::class.java)?.defaultDisplay?.refreshRate
            }
        }.getOrNull() ?: 60f
        return hz.coerceIn(30f, 120f)
    }

    private fun startSample() {
        if (!sampleRunning.compareAndSet(false, true)) return
        sampleThread = thread(name = "tiny1b-sample", isDaemon = true) {
            var t = 0f
            var slot = 0
            _state.update {
                it.copy(
                    status = DeviceStatus.Sample,
                    statusDetail = "样例画面（无模组）",
                    errorMessage = null,
                    measureEdit = false,
                )
            }
            try {
                while (running.get() && sampleRunning.get() && settings.samplePreview && _state.value.status != DeviceStatus.Live) {
                    val buf = sampleScratch[slot]
                    SyntheticScene.uvcFrame(t = t, dest = buf)
                    latestFrame.set(buf)
                    slot = 1 - slot
                    postNativeAvailable()
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

    private fun emitDuePacedFrame() {
        if (!pacingActive) return
        val extras = scheduledExtras
        val prev = prevHold
        val curr = currHold
        if (extras <= 0 || prev == null || curr == null) {
            if (curr != null && !shownCurr) {
                shownCurr = true
                processPlanes(curr)
            }
            stopPacing()
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - pairOriginMs
        val due = FrameGeneration.dueDisplayStep(elapsed, pairDtMs, extras)
        if (due <= 0) return
        if (!shownCurr && due >= extras + 1) {
            shownCurr = true
            nextBlendK = extras + 1
            processPlanes(curr)
            stopPacing()
            return
        }
        if (nextBlendK <= extras && due >= nextBlendK) {
            val k = due.coerceAtMost(extras)
            val blended = FrameGeneration.blend(prev, curr, FrameGeneration.blendT(k, extras), blendHold)
            blendHold = blended
            nextBlendK = k + 1
            processPlanes(blended)
        }
    }

    private fun ingestNativeFrame(frame: ByteArray) {
        if (!previewing && !sampleRunning.get()) return
        val liveStatus = _state.value.status
        if (liveStatus != DeviceStatus.Live && liveStatus != DeviceStatus.Sample) return
        val now = SystemClock.elapsedRealtime()
        if (lastNativeAt != 0L) {
            nativeIntervalMs = ((nativeIntervalMs * 3 + (now - lastNativeAt)) / 4).coerceIn(20L, 200L)
        }
        lastNativeAt = now
        val parsed = runCatching { FrameParser.parseUvcFrame(frame, parseLum, parseKel) }.getOrNull() ?: return
        parseLum = parsed.luminance
        parseKel = parsed.kelvin16
        val oriented = FrameParser.applyOrientation(parsed, settings.rotation, settings.mirror)
        val requested = settings.frameGenScale.extraFrames
        val extras = FrameGeneration.pacedExtraFrames(requested, nativeIntervalMs, displayRefreshHz())
        val oldCurr = currHold
        val useGen = extras > 0 &&
            oldCurr != null &&
            oldCurr.width == oriented.width &&
            oldCurr.height == oriented.height
        if (useGen) {
            ispHandler?.removeMessages(MSG_VSYNC)
            if (!shownCurr) {
                shownCurr = true
                processPlanes(oldCurr)
            }
            val recycled = prevHold
            prevHold = oldCurr
            currHold = copyPlanesInto(oriented, if (recycled !== oldCurr) recycled else null)
            pairOriginMs = now
            pairDtMs = nativeIntervalMs
            scheduledExtras = extras
            nextBlendK = 1
            shownCurr = false
            startPacing()
        } else {
            cancelPacing(showHeldNative = false)
            prevHold = null
            currHold = copyPlanesInto(oriented, currHold)
            shownCurr = true
            processPlanes(currHold!!)
        }
    }

    private fun copyPlanesInto(src: ThermalPlanes, dst: ThermalPlanes?): ThermalPlanes {
        if (
            dst != null &&
            dst.width == src.width &&
            dst.height == src.height &&
            dst.luminance.size == src.pixelCount &&
            dst.kelvin16.size == src.pixelCount &&
            dst.luminance !== src.luminance &&
            dst.kelvin16 !== src.kelvin16
        ) {
            src.luminance.copyInto(dst.luminance)
            src.kelvin16.copyInto(dst.kelvin16)
            return dst
        }
        return src.copyPlanes()
    }

    private fun processPlanes(planes: ThermalPlanes) {
        if (!previewing && !sampleRunning.get()) return
        val liveStatus = _state.value.status
        if (liveStatus != DeviceStatus.Live && liveStatus != DeviceStatus.Sample) return
        lastPlanes = planes
        val palette = Palettes.get(settings.paletteId)
        val spanFixed = settings.spanFixed
        val spanLo = settings.spanLowC
        val spanHi = settings.spanHighC
        val rendered = SuperResolution.enhance(
            planes,
            settings.isrScale,
            palette,
            denoiseAmount = settings.denoiseAmount,
            scratch = ispScratch,
            sharpenAmount = settings.sharpenAmount,
            spanLowC = if (spanFixed) spanLo else null,
            spanHighC = if (spanFixed) spanHi else null,
        )
        val snap = measurement.snapshot(planes)
        val bmp = obtainLiveBitmap(rendered.width, rendered.height)
        bmp.setPixels(rendered.argb, 0, rendered.width, 0, 0, rendered.width, rendered.height)
        if (recorder.recording) {
            runCatching { recorder.offer(bmp) }
        }
        if (!previewing && !sampleRunning.get()) return
        val status = _state.value.status
        if (status != DeviceStatus.Live && status != DeviceStatus.Sample) return
        frames++
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1000L) {
            displayedFps = frames
            frames = 0
            fpsWindowStart = now
        }
        val minC = if (spanFixed) spanLo else (snap.stats?.min?.celsius ?: 0f)
        val maxC = if (spanFixed) spanHi else (snap.stats?.max?.celsius ?: 0f)
        _state.update {
            it.copy(
                bitmap = bmp,
                measurement = snap,
                fps = displayedFps,
                palette = settings.paletteId,
                isr = settings.isrScale,
                frameGen = settings.frameGenScale,
                markerOpacity = settings.markerOpacity,
                sharpenAmount = settings.sharpenAmount,
                denoiseAmount = settings.denoiseAmount,
                spanFixed = spanFixed,
                spanLowC = spanLo,
                spanHighC = spanHi,
                colorBarMin = minC,
                colorBarMax = maxC,
                userPointCount = snap.points.count { p -> p.kind == PointKind.USER },
            )
        }
    }

    private fun obtainLiveBitmap(width: Int, height: Int): Bitmap {
        val shown = _state.value.bitmap
        var idx = (liveBmpSlot + 1) % LIVE_BITMAPS
        if (liveBitmaps[idx] === shown) idx = (idx + 1) % LIVE_BITMAPS
        val existing = liveBitmaps[idx]
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            liveBmpSlot = idx
            return existing
        }
        if (existing != null && existing !== shown && !existing.isRecycled) {
            runCatching { existing.recycle() }
        }
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        liveBitmaps[idx] = created
        liveBmpSlot = idx
        return created
    }

    private fun recycleLiveBitmaps(except: Bitmap?, forceAll: Boolean) {
        for (i in liveBitmaps.indices) {
            val bmp = liveBitmaps[i] ?: continue
            if (!forceAll && bmp === except) continue
            if (!bmp.isRecycled) runCatching { bmp.recycle() }
            liveBitmaps[i] = null
        }
    }

    fun cacheSizeBytes(): Long = AppCache.sizeBytes(appContext)

    fun clearCache(keep: Set<File>): Long {
        val live = _state.value.status == DeviceStatus.Live || _state.value.status == DeviceStatus.Sample
        if (live) {
            prevHold = null
            cancelPacing(showHeldNative = false)
        } else {
            recycleLiveBitmaps(except = _state.value.bitmap, forceAll = false)
            prevHold = null
            currHold = null
            blendHold = null
            lastPlanes = null
        }
        return AppCache.clear(appContext, keep)
    }

    private fun readSettings(base: EngineState): EngineState = base.copy(
        palette = settings.paletteId,
        isr = settings.isrScale,
        showCenter = settings.showCenter,
        showMinMax = settings.showMinMax,
        mirror = settings.mirror,
        useFahrenheit = settings.useFahrenheit,
        samplePreview = settings.samplePreview,
        useDownloadMirror = settings.useDownloadMirror,
        denoiseAmount = settings.denoiseAmount,
        rotation = settings.rotation,
        frameGen = settings.frameGenScale,
        markerOpacity = settings.markerOpacity,
        sharpenAmount = settings.sharpenAmount,
        spanFixed = settings.spanFixed,
        spanLowC = settings.spanLowC,
        spanHighC = settings.spanHighC,
        shutterMaxSeconds = settings.shutterMaxSeconds,
    )

    private fun stopRecordingInternal(disconnectMessage: String?) {
        usbHandler.removeCallbacks(recTick)
        recordingStartedAt = 0L
        if (!recorder.recording) {
            _state.update { it.copy(recording = false) }
            if (disconnectMessage != null) flashCaptureHint(disconnectMessage)
            return
        }
        val result = runCatching { recorder.stop() }
        _state.update { it.copy(recording = false) }
        val message = when {
            result.isSuccess -> disconnectMessage ?: "录像已保存到相册"
            else -> "保存录像失败：${result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName}"
        }
        flashCaptureHint(message)
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (!recorder.recording) return
            val sec = ((SystemClock.elapsedRealtime() - recordingStartedAt) / 1000L).toInt().coerceAtLeast(0)
            _state.update { it.copy(recording = true, captureHint = "录像中 ${formatMmSs(sec)}") }
            usbHandler.postDelayed(this, 1000)
        }
    }

    private val presenceCheckRunnable = object : Runnable {
        override fun run() {
            if (!running.get() || !previewing) return
            if (findModule() == null) {
                onUsbDetach(null)
                return
            }
            usbHandler.postDelayed(this, PRESENCE_MS)
        }
    }

    private val clearHintRunnable = Runnable {
        if (recorder.recording) return@Runnable
        _state.update { it.copy(captureHint = null) }
    }

    companion object {
        private const val TAG = "ThermalEngine"
        private const val RETRY_MS = 5000L
        private const val HINT_MS = 3500L
        private const val PRESENCE_MS = 1000L
        private const val UVC_SCRATCH = 3
        private const val LIVE_BITMAPS = 3
        private const val MSG_NATIVE = 1
        private const val MSG_VSYNC = 2

        private fun formatMmSs(totalSec: Int): String {
            val m = totalSec / 60
            val s = totalSec % 60
            return "%02d:%02d".format(m, s)
        }
    }
}
