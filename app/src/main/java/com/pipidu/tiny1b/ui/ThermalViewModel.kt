package com.pipidu.tiny1b.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pipidu.tiny1b.AppContainer
import com.pipidu.tiny1b.core.DisplayRotation
import com.pipidu.tiny1b.core.FrameGenScale
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.core.PaletteId
import com.pipidu.tiny1b.device.EngineState
import com.pipidu.tiny1b.device.ThermalEngine
import com.pipidu.tiny1b.data.AppCache
import com.pipidu.tiny1b.update.AppUpdater
import com.pipidu.tiny1b.update.UpdateStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ThermalViewModel(
    private val engine: ThermalEngine,
    private val updater: AppUpdater,
) : ViewModel() {
    val ui: StateFlow<EngineState> = engine.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        engine.state.value,
    )
    val updateStatus: StateFlow<UpdateStatus> = updater.status
    val currentVersion: String = updater.currentVersion
    val currentVersionCode: Int = updater.currentVersionCode
    private var downloadJob: Job? = null

    fun start() = engine.start()
    fun stop() = engine.stop()
    fun bindUsb(activity: android.app.Activity) = engine.bindActivity(activity)
    fun unbindUsb(activity: android.app.Activity) = engine.unbindActivity(activity)
    fun retry() = engine.retryConnect()
    fun onLaunchIntent(intent: android.content.Intent?) {
        if (!engine.onLaunchIntent(intent)) {
            engine.retryConnect()
        }
    }
    fun shutter() = engine.shutter()
    fun setKbCalibrate(enabled: Boolean) = engine.setKbCalibrate(enabled)
    fun applyShutterMax(seconds: Int) = engine.applyShutterMax(seconds)
    fun setPalette(id: PaletteId) = engine.setPalette(id)
    fun setIsr(scale: IsrScale) = engine.setIsr(scale)
    fun setFrameGen(scale: FrameGenScale) = engine.setFrameGen(scale)
    fun setShowCenter(value: Boolean) = engine.setShowCenter(value)
    fun setShowMinMax(value: Boolean) = engine.setShowMinMax(value)
    fun setMirror(value: Boolean) = engine.setMirror(value)
    fun setRotation(value: DisplayRotation) = engine.setRotation(value)
    fun cycleRotation() = engine.cycleRotation()
    fun setFahrenheit(value: Boolean) = engine.setFahrenheit(value)
    fun setSamplePreview(value: Boolean) = engine.setSamplePreview(value)
    fun setDenoise(value: Boolean) = engine.setDenoise(value)
    fun setMarkerOpacity(percent: Int) = engine.setMarkerOpacity(percent)
    fun setSharpenAmount(percent: Int) = engine.setSharpenAmount(percent)
    fun setSpanFixed(value: Boolean) = engine.setSpanFixed(value)
    fun setSpanLowC(celsius: Float) = engine.setSpanLowC(celsius)
    fun setSpanHighC(celsius: Float) = engine.setSpanHighC(celsius)
    fun setMeasureEdit(value: Boolean) = engine.setMeasureEdit(value)
    fun addOrSelectPoint(nx: Float, ny: Float) = engine.addOrSelectPoint(nx, ny)
    fun moveUser(id: Long, nx: Float, ny: Float) = engine.moveUser(id, nx, ny)
    fun beginDrag(nx: Float, ny: Float) = engine.beginDrag(nx, ny)
    fun removeNearest(nx: Float, ny: Float) = engine.removeNearest(nx, ny)
    fun removeSelected() = engine.removeSelected()
    fun clearUserPoints() = engine.clearUserPoints()
    fun capturePhoto() = engine.capturePhoto()
    fun toggleRecord() = engine.toggleRecord()
    fun onStorageDenied() = engine.flashCaptureHint("需要存储权限才能保存照片和录像")

    fun clearCache(): String {
        val keep = updater.protectedCacheFiles()
        val downloading = updater.status.value is UpdateStatus.Downloading
        val freed = engine.clearCache(keep)
        updater.onDiskCacheCleared()
        return when {
            downloading -> "已清理缓存（更新下载未中断）"
            freed <= 0L -> "缓存已是空的"
            else -> "已清理 ${AppCache.formatSize(freed)}"
        }
    }

    fun checkUpdate() = viewModelScope.launch { updater.check() }
    fun downloadUpdate() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch { updater.download() }
    }
    fun installPermissionIntent() = updater.installPermissionIntent()
    fun installIntent() = updater.installIntent()
    fun onHostResumed() {
        engine.onActivityResumed()
        updater.onInstallPermissionResult()
    }
    fun onHostPaused() = engine.onActivityPaused()

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ThermalViewModel(container.engine, container.updater) as T
                }
            }
    }
}
