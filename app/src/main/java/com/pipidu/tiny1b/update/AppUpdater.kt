package com.pipidu.tiny1b.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.pipidu.tiny1b.BuildConfig
import com.pipidu.tiny1b.core.AppVersion
import com.pipidu.tiny1b.core.GithubRelease
import com.pipidu.tiny1b.core.GithubReleaseParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed class UpdateStatus {
    data object Idle : UpdateStatus()
    data object Checking : UpdateStatus()
    data object UpToDate : UpdateStatus()
    data class Available(val release: GithubRelease) : UpdateStatus()
    data class Downloading(val release: GithubRelease, val progress: Float) : UpdateStatus()
    data class Ready(val release: GithubRelease, val apk: File) : UpdateStatus()
    data class NeedsPermission(val release: GithubRelease, val apk: File?) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

class AppUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME.substringBefore("-")
    val currentVersionCode: Int = BuildConfig.VERSION_CODE

    suspend fun check() {
        _status.value = UpdateStatus.Checking
        try {
            val json = withContext(Dispatchers.IO) { httpGet(API_LATEST) }
            val release = GithubReleaseParser.parseLatest(json)
                ?: throw IllegalStateException("发布页没有可安装的 APK")
            _status.value = if (AppVersion.isNewer(release.version, currentVersion)) {
                UpdateStatus.Available(release)
            } else {
                UpdateStatus.UpToDate
            }
        } catch (error: Throwable) {
            _status.value = UpdateStatus.Error(error.message ?: "检查更新失败")
        }
    }

    suspend fun download() {
        val release = when (val current = _status.value) {
            is UpdateStatus.Available -> current.release
            is UpdateStatus.NeedsPermission -> current.release
            is UpdateStatus.Ready -> current.release
            is UpdateStatus.Error, UpdateStatus.Idle, UpdateStatus.Checking, UpdateStatus.UpToDate -> {
                check()
                ( _status.value as? UpdateStatus.Available)?.release
                    ?: return
            }
            is UpdateStatus.Downloading -> return
        }
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val dest = File(dir, "TINY1-B-${release.version}.apk")
                httpDownload(release.apkUrl, dest) { read, total ->
                    val p = if (total > 0) (read.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    _status.value = UpdateStatus.Downloading(release, p)
                }
                dest
            }
            if (canInstall()) {
                _status.value = UpdateStatus.Ready(release, file)
            } else {
                _status.value = UpdateStatus.NeedsPermission(release, file)
            }
        } catch (error: Throwable) {
            _status.value = UpdateStatus.Error(error.message ?: "下载失败")
        }
    }

    fun installPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        )
    }

    fun installIntent(): Intent? {
        val apk = when (val current = _status.value) {
            is UpdateStatus.Ready -> current.apk
            is UpdateStatus.NeedsPermission -> current.apk
            else -> null
        } ?: return null
        if (!canInstall()) return null
        val uri = FileProvider.getUriForFile(appContext, AUTHORITY, apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun onInstallPermissionResult() {
        val current = _status.value
        if (current is UpdateStatus.NeedsPermission && current.apk != null && canInstall()) {
            _status.value = UpdateStatus.Ready(current.release, current.apk)
        }
    }

    fun canInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun reset() {
        _status.value = UpdateStatus.Idle
    }

    private fun httpGet(url: String): String {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub 返回 $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpDownload(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载失败 $code")
            }
            val total = connection.contentLengthLong
            dest.outputStream().use { out ->
                connection.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        readTotal += n
                        onProgress(readTotal, total)
                    }
                }
            }
            if (dest.length() < 64) {
                dest.delete()
                throw IllegalStateException("APK 文件无效")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "TINY1-B/${currentVersion}")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        return connection
    }

    companion object {
        const val REPO = "pipidu/TINY1-B"
        const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
        const val AUTHORITY = "com.pipidu.tiny1b.fileprovider"
    }
}
