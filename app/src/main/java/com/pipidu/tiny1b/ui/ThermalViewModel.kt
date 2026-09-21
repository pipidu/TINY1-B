package com.pipidu.tiny1b.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pipidu.tiny1b.AppContainer
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.core.PaletteId
import com.pipidu.tiny1b.device.EngineState
import com.pipidu.tiny1b.device.ThermalEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ThermalViewModel(
    private val engine: ThermalEngine,
) : ViewModel() {
    val ui: StateFlow<EngineState> = engine.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        engine.state.value,
    )

    fun start() = engine.start()
    fun stop() = engine.stop()
    fun retry() = engine.retryConnect()
    fun requestUsbPermission() = engine.requestUsbPermission()
    fun shutter() = engine.shutter()
    fun setKbCalibrate(enabled: Boolean) = engine.setKbCalibrate(enabled)
    fun applyShutterMax(seconds: Int) = engine.applyShutterMax(seconds)
    fun setPalette(id: PaletteId) = engine.setPalette(id)
    fun setIsr(scale: IsrScale) = engine.setIsr(scale)
    fun setShowCenter(value: Boolean) = engine.setShowCenter(value)
    fun setShowMinMax(value: Boolean) = engine.setShowMinMax(value)
    fun setMirror(value: Boolean) = engine.setMirror(value)
    fun setFahrenheit(value: Boolean) = engine.setFahrenheit(value)
    fun setSamplePreview(value: Boolean) = engine.setSamplePreview(value)
    fun setDenoise(value: Boolean) = engine.setDenoise(value)
    fun setMeasureEdit(value: Boolean) = engine.setMeasureEdit(value)
    fun addOrSelectPoint(nx: Float, ny: Float) = engine.addOrSelectPoint(nx, ny)
    fun moveUser(id: Long, nx: Float, ny: Float) = engine.moveUser(id, nx, ny)
    fun beginDrag(nx: Float, ny: Float) = engine.beginDrag(nx, ny)
    fun removeNearest(nx: Float, ny: Float) = engine.removeNearest(nx, ny)
    fun removeSelected() = engine.removeSelected()
    fun clearUserPoints() = engine.clearUserPoints()

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ThermalViewModel(container.engine) as T
                }
            }
    }
}
