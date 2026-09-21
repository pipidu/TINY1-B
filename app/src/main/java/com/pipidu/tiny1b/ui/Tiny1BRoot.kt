package com.pipidu.tiny1b.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pipidu.tiny1b.ui.live.LiveViewScreen
import com.pipidu.tiny1b.ui.settings.SettingsScreen
import com.pipidu.tiny1b.ui.theme.Paper

@Composable
fun Tiny1BRoot(viewModel: ThermalViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        if (showSettings) {
            SettingsScreen(
                state = state,
                updateStatus = updateStatus,
                currentVersion = viewModel.currentVersion,
                currentVersionCode = viewModel.currentVersionCode,
                onBack = { showSettings = false },
                onIsr = viewModel::setIsr,
                onShowCenter = viewModel::setShowCenter,
                onShowMinMax = viewModel::setShowMinMax,
                onMirror = viewModel::setMirror,
                onFahrenheit = viewModel::setFahrenheit,
                onSample = viewModel::setSamplePreview,
                onDenoise = viewModel::setDenoise,
                onShutterMax = viewModel::applyShutterMax,
                onKbCal = viewModel::setKbCalibrate,
                onCheckUpdate = viewModel::checkUpdate,
                onDownloadUpdate = viewModel::downloadUpdate,
                onInstallPermission = viewModel::installPermissionIntent,
                onInstall = viewModel::installIntent,
            )
        } else {
            LiveViewScreen(
                state = state,
                onOpenSettings = { showSettings = true },
                onRetry = viewModel::retry,
                onShutter = viewModel::shutter,
                onPalette = viewModel::setPalette,
                onIsr = viewModel::setIsr,
                onMeasureEdit = viewModel::setMeasureEdit,
                onAddOrSelect = viewModel::addOrSelectPoint,
                onBeginDrag = viewModel::beginDrag,
                onMoveUser = viewModel::moveUser,
                onRemoveNearest = viewModel::removeNearest,
                onRemoveSelected = viewModel::removeSelected,
                onClearPoints = viewModel::clearUserPoints,
            )
        }
    }
}
