package com.pipidu.tiny1b.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.pipidu.tiny1b.BuildConfig
import com.pipidu.tiny1b.core.AppVersion
import com.pipidu.tiny1b.core.GithubDownloadMirror
import com.pipidu.tiny1b.core.GithubRelease
import com.pipidu.tiny1b.core.GithubReleaseParser
import com.pipidu.tiny1b.core.OneShotGate
import com.pipidu.tiny1b.data.AppCache
import com.pipidu.tiny1b.data.AppSettings
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

class AppUpdater(context: Context, private val settings: AppSettings) {
    private val appContext = context.applicationContext
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME.substringBefore("-")
    val currentVersionCode: Int = BuildConfig.VERSION_CODE
    private val userAgent = "TINY1-B/$currentVersion (+https://github.com/$REPO)"
    private val downloadGate = OneShotGate()

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
            Log.e(TAG, "check", error)
            _status.value = UpdateStatus.Error(error.message ?: "检查更新失败")
        }
    }

    suspend fun download() {
        if (!downloadGate.tryEnter()) return
        try {
            val release = when (val current = _status.value) {
                is UpdateStatus.Available -> current.release
                is UpdateStatus.NeedsPermission -> current.release
                is UpdateStatus.Ready -> current.release
                is UpdateStatus.Downloading -> return
                else -> {
                    check()
                    when (val after = _status.value) {
                        is UpdateStatus.Available -> after.release
                        is UpdateStatus.UpToDate -> return
                        is UpdateStatus.Error -> return
                        else -> {
                            _status.value = UpdateStatus.Error("没有可下载的新版本")
                            return
                        }
                    }
                }
            }
            _status.value = UpdateStatus.Downloading(release, 0f)
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = AppCache.updatesDir(appContext).apply { mkdirs() }
                    val dest = File(dir, "TINY1-B-${release.version}.apk")
                    pruneUpdates(keep = dest, alsoKeepPart = true)
                    if (dest.exists() && dest.length() > 64 &&
                        (release.sizeBytes <= 0L || dest.length() == release.sizeBytes)
                    ) {
                        return@withContext dest
                    }
                    httpDownload(release.apkUrl, dest, mirror = settings.useDownloadMirror) { read, total ->
                        val p = if (total > 0) (read.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                        _status.value = UpdateStatus.Downloading(release, p)
                    }
                    pruneUpdates(keep = dest, alsoKeepPart = false)
                    dest
                }
                if (canInstall()) {
                    _status.value = UpdateStatus.Ready(release, file)
                } else {
                    _status.value = UpdateStatus.NeedsPermission(release, file)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "download", error)
                _status.value = UpdateStatus.Error(error.message ?: "下载失败")
            }
        } finally {
            downloadGate.exit()
        }
    }

    fun installPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun installIntent(): Intent? {
        val apk = when (val current = _status.value) {
            is UpdateStatus.Ready -> current.apk
            is UpdateStatus.NeedsPermission -> current.apk
            else -> null
        } ?: return null
        if (!apk.exists() || apk.length() < 64) {
            _status.value = UpdateStatus.Error("安装包不存在，请重新下载")
            return null
        }
        if (!canInstall()) return null
        val uri = FileProvider.getUriForFile(appContext, AUTHORITY, apk)
        INSTALLER_PACKAGES.forEach { pkg ->
            runCatching {
                appContext.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
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

    fun protectedCacheFiles(): Set<File> {
        val dir = AppCache.updatesDir(appContext)
        return when (val current = _status.value) {
            is UpdateStatus.Downloading -> setOf(
                File(dir, "TINY1-B-${current.release.version}.apk"),
                File(dir, "TINY1-B-${current.release.version}.apk.part"),
            )
            else -> emptySet()
        }
    }

    fun onDiskCacheCleared() {
        when (val current = _status.value) {
            is UpdateStatus.Downloading -> return
            is UpdateStatus.Ready -> if (!current.apk.exists()) _status.value = UpdateStatus.Idle
            is UpdateStatus.NeedsPermission -> {
                if (current.apk == null || !current.apk.exists()) {
                    _status.value = UpdateStatus.Idle
                }
            }
            else -> Unit
        }
    }

    private fun pruneUpdates(keep: File, alsoKeepPart: Boolean) {
        val dir = keep.parentFile ?: return
        val keepCanon = runCatching { keep.canonicalFile }.getOrDefault(keep)
        val partCanon = runCatching { File(keep.path + ".part").canonicalFile }.getOrNull()
        dir.listFiles()?.forEach { child ->
            val canon = runCatching { child.canonicalFile }.getOrDefault(child)
            if (canon == keepCanon) return@forEach
            if (alsoKeepPart && partCanon != null && canon == partCanon) return@forEach
            child.delete()
        }
    }

    private fun httpGet(url: String): String {
        val connection = openFollowing(
            url = url,
            accept = "application/vnd.github+json",
            githubApiHeaders = true,
            readTimeoutMs = 20_000,
            mirror = false,
        )
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpDownload(
        url: String,
        dest: File,
        mirror: Boolean,
        onProgress: (Long, Long) -> Unit,
    ) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()
        val connection = openFollowing(
            url = GithubDownloadMirror.rewrite(url, mirror),
            accept = "*/*",
            githubApiHeaders = false,
            readTimeoutMs = 120_000,
            mirror = mirror,
        )
        try {
            val total = connection.contentLengthLong
            tmp.outputStream().use { out ->
                connection.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
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
            if (tmp.length() < 64) {
                tmp.delete()
                throw IllegalStateException("APK 文件无效")
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowing(
        url: String,
        accept: String,
        githubApiHeaders: Boolean,
        readTimeoutMs: Int,
        mirror: Boolean,
        maxRedirects: Int = 6,
    ): HttpURLConnection {
        var current = url
        var currentAccept = accept
        var sendApiVersion = githubApiHeaders
        repeat(maxRedirects) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", currentAccept)
            if (sendApiVersion) {
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = try {
                connection.responseCode
            } catch (error: Throwable) {
                connection.disconnect()
                throw IllegalStateException("网络错误：${error.message ?: error.javaClass.simpleName}")
            }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("下载重定向缺少 Location（$code）")
                }
                current = GithubDownloadMirror.rewrite(resolveRedirect(current, location), mirror)
                currentAccept = "*/*"
                sendApiVersion = false
                return@repeat
            }
            if (code !in 200..299) {
                val detail = runCatching {
                    (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.readText()
                }.getOrNull()?.take(120).orEmpty()
                connection.disconnect()
                throw IllegalStateException(
                    when (code) {
                        403 -> if (mirror) {
                            "镜像拒绝请求（403）。请稍后重试，或关闭「使用镜像下载」。"
                        } else {
                            "GitHub 拒绝请求（403）。请检查网络后重试。"
                        }
                        404 -> "未找到发布页（404）。"
                        else -> "GitHub 返回 $code${if (detail.isBlank()) "" else "：$detail"}"
                    },
                )
            }
            return connection
        }
        throw IllegalStateException("下载重定向过多")
    }

    private fun resolveRedirect(current: String, location: String): String {
        return if (location.startsWith("http://") || location.startsWith("https://")) {
            location
        } else {
            URL(URL(current), location).toString()
        }
    }

    companion object {
        const val REPO = "pipidu/TINY1-B"
        const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
        const val AUTHORITY = "com.pipidu.tiny1b.fileprovider"
        private const val TAG = "AppUpdater"
        private val INSTALLER_PACKAGES = listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
        )
    }
}
