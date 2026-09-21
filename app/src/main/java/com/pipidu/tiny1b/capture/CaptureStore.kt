package com.pipidu.tiny1b.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object CaptureStore {
    private const val ALBUM = "TINY1-B"

    fun stamp(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))

    fun saveJpeg(context: Context, bitmap: Bitmap): Uri {
        val name = "TINY1-B_${stamp()}.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建照片")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                        throw IllegalStateException("JPEG 编码失败")
                    }
                } ?: throw IllegalStateException("无法写入照片")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                return uri
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建相册目录")
        }
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw IllegalStateException("JPEG 编码失败")
            }
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        return Uri.fromFile(file)
    }

    fun createVideoSink(context: Context): VideoSink {
        val name = "TINY1-B_${stamp()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建录像")
            val pfd = resolver.openFileDescriptor(uri, "w")
                ?: run {
                    runCatching { resolver.delete(uri, null, null) }
                    throw IllegalStateException("无法写入录像")
                }
            return VideoSink(uri = uri, file = null, pfd = pfd)
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建录像目录")
        }
        return VideoSink(uri = null, file = File(dir, name), pfd = null)
    }

    fun finishVideo(context: Context, sink: VideoSink, success: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = sink.uri ?: return
            if (success) {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
            } else {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            return
        }
        val file = sink.file ?: return
        if (success) {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        } else {
            runCatching { file.delete() }
        }
    }
}

internal class VideoSink(
    val uri: Uri?,
    val file: File?,
    val pfd: ParcelFileDescriptor?,
)
