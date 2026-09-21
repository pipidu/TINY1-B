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
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import com.pipidu.tiny1b.core.FrameParser
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.core.MeasurementModel
import com.pipidu.tiny1b.core.MeasurementSnapshot
import com.pipidu.tiny1b.core.PaletteId
import com.pipidu.tiny1b.core.Palettes
import com.pipidu.tiny1b.core.PointKind
import com.pipidu.tiny1b.core.RenderedFrame
import com.pipidu.tiny1b.core.SuperResolution
import com.pipidu.tiny1b.core.SyntheticScene
import com.pipidu.tiny1b.core.ThermalPlanes
import com.pipidu.tiny1b.core.Tiny1BFormat
import com.pipidu.tiny1b.data.AppSettings
import com.zz.infisense.camera.IFrameCallback
import com.zz.infisense.camera.UVCCamera
import com.zz.infisense.camera.UsbControlBlock
import java.util.concurrent.atomic.AtomicBoolean
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
    val denoise: Boolean = false,
    val shutterMaxSeconds: Int = 30,
    val colorBarMin: Float = 0f,
    val colorBarMax: Float = 40f,
    val userPointCount: Int = 0,
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
    private val frameLock = Object()
    private val cameraLock = Any()
    private var worker: Thread? = null
    private var sampleThread: Thread? = null
    @Volatile private var previewing = false
    @Volatile private var nativeLoadFailed = false
    private var frames = 0
    private var fpsWindowStart = 0L
    private var displayedFps = 0
    private var activity: Activity? = null
    private var camera: UVCCamera? = null
    private var usbReceiverRegistered = false

    private val _state = MutableStateFlow(readSettings(EngineState()))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val frameCallback = IFrameCallback { frame ->
        if (frame == null || frame.size < Tiny1BFormat.UVC_FRAME_BYTES) return@IFrameCallback
        latestFrame.set(frame.copyOf(Tiny1BFormat.UVC_FRAME_BYTES))
        synchronized(frameLock) { frameLock.notify() }
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
                UsbControlBlock.USB_ATTACH -> tryOpenCamera("attach")
                UsbControlBlock.USB_DETACH -> onUsbDetach()
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
                    usbHandler.obtainMessage(UsbControlBlock.USB_ATTACH).sendToTarget()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    usbHandler.obtainMessage(UsbControlBlock.USB_DETACH).sendToTarget()
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
        startWorker()
        if (settings.samplePreview) startSample()
    }

    fun stop() {
        running.set(false)
        usbHandler.removeCallbacks(attachRetryRunnable)
        unregisterUsbReceiver()
        synchronized(cameraLock) { destroyCameraLocked() }
        stopSample()
        synchronized(frameLock) { frameLock.notifyAll() }
        worker?.join(500)
        worker = null
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
            unregisterUsbReceiver()
            this.activity = null
        }
    }

    fun onLaunchIntent(intent: Intent?): Boolean {
        if (!running.get()) return false
        retryConnect()
        return intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
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
        unregisterUsbReceiver()
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

    private fun tryOpenCamera(reason: String) {
        if (!running.get() || nativeLoadFailed) return
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
                    stopPreviewLocked()
                    destroyCameraLocked()
                }
                showSearching()
                if (settings.samplePreview) startSample()
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

    private fun onUsbDetach() {
        synchronized(cameraLock) {
            previewing = false
            destroyCameraLocked()
        }
        _state.update {
            it.copy(
                status = DeviceStatus.Searching,
                statusDetail = "模组已断开",
                bitmap = null,
                errorMessage = null,
            )
        }
        if (settings.samplePreview) startSample()
        usbHandler.removeCallbacks(attachRetryRunnable)
        usbHandler.postDelayed(attachRetryRunnable, RETRY_MS)
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
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == Tiny1BFormat.VENDOR_ID && it.productId == Tiny1BFormat.PRODUCT_ID
        }

    private fun registerUsbReceiver() {
        val act = activity ?: return
        if (usbReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            act.registerReceiver(usbStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            act.registerReceiver(usbStateReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    private fun unregisterUsbReceiver() {
        val act = activity
        if (!usbReceiverRegistered || act == null) {
            usbReceiverRegistered = false
            return
        }
        runCatching { act.unregisterReceiver(usbStateReceiver) }
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
                status = if (settings.samplePreview) it.status else DeviceStatus.Searching,
                statusDetail = "未检测到 Tiny1-B",
                errorMessage = if (settings.samplePreview) it.errorMessage else null,
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
            )
        }
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
        private const val RETRY_MS = 5000L
    }
}
