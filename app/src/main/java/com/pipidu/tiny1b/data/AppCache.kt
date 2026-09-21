package com.pipidu.tiny1b.data

import android.content.Context
import java.io.File

/**
 * Disk cache for software artifacts (update APKs, temp files). Does not include
 * live USB / JNI buffers, gallery photos, or SharedPreferences.
 */
object AppCache {
    const val UPDATES_DIR = "updates"

    fun updatesDir(context: Context): File = File(context.cacheDir, UPDATES_DIR)

    fun sizeBytes(context: Context): Long {
        var total = sizeOf(context.cacheDir)
        context.externalCacheDir?.let { total += sizeOf(it) }
        return total
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "${bytes} B"
        if (bytes < 1024L * 1024L) return "%.1f KB".format(bytes / 1024.0)
        return "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    /**
     * Delete cacheDir / externalCacheDir files except [keep] (in-progress APK).
     * Returns bytes actually removed.
     */
    fun clear(context: Context, keep: Set<File> = emptySet()): Long {
        val keepCanon = keep.mapNotNull { file ->
            runCatching { file.canonicalFile }.getOrNull()
        }.toSet()
        var freed = clearTree(context.cacheDir, keepCanon)
        context.externalCacheDir?.let { freed += clearTree(it, keepCanon) }
        return freed
    }

    private fun sizeOf(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length().coerceAtLeast(0L)
        val children = file.listFiles() ?: return 0L
        var total = 0L
        for (child in children) total += sizeOf(child)
        return total
    }

    private fun clearTree(root: File?, keep: Set<File>): Long {
        if (root == null || !root.exists()) return 0L
        val children = root.listFiles() ?: return 0L
        var freed = 0L
        for (child in children) {
            val canon = runCatching { child.canonicalFile }.getOrDefault(child)
            if (isProtected(canon, keep)) {
                if (child.isDirectory) freed += clearTree(child, keep)
                continue
            }
            if (child.isDirectory) {
                freed += clearTree(child, keep)
                if (child.listFiles().isNullOrEmpty()) {
                    child.delete()
                }
            } else {
                val n = child.length().coerceAtLeast(0L)
                if (child.delete()) freed += n
            }
        }
        return freed
    }

    private fun isProtected(canon: File, keep: Set<File>): Boolean {
        for (k in keep) {
            if (canon == k) return true
            val kp = k.path
            val cp = canon.path
            if (kp.startsWith("$cp/") || cp.startsWith("$kp/")) return true
        }
        return false
    }
}
