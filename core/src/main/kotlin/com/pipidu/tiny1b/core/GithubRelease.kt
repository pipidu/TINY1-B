package com.pipidu.tiny1b.core

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(raw: String): AppVersion? {
            val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore("-")
            val parts = cleaned.split(".")
            if (parts.size < 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return AppVersion(major, minor, patch)
        }

        fun isNewer(candidate: String, current: String): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(current) ?: return false
            return a > b
        }
    }
}

data class GithubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
) {
    val version: String get() = AppVersion.parse(tagName)?.toString() ?: tagName.removePrefix("v")
}

object GithubReleaseParser {
    fun parseLatest(json: String): GithubRelease? {
        val tag = stringField(json, "tag_name") ?: return null
        val name = stringField(json, "name") ?: tag
        val body = stringField(json, "body").orEmpty()
        val assets = assetBlock(json) ?: return null
        val apkName = stringField(assets, "name") { it.endsWith(".apk", ignoreCase = true) } ?: return null
        val apkUrl = stringField(assets, "browser_download_url") { it.contains(".apk", ignoreCase = true) }
            ?: return null
        val size = numberField(assets, "size") ?: 0L
        return GithubRelease(
            tagName = tag,
            name = name,
            body = body.replace("\\n", "\n").replace("\\r", ""),
            apkUrl = apkUrl,
            apkName = apkName,
            sizeBytes = size,
        )
    }

    private fun assetBlock(json: String): String? {
        val start = json.indexOf("\"assets\"")
        if (start < 0) return null
        val bracket = json.indexOf('[', start)
        if (bracket < 0) return null
        val end = json.indexOf(']', bracket)
        if (end < 0) return null
        return json.substring(bracket, end + 1)
    }

    private fun stringField(
        json: String,
        key: String,
        predicate: (String) -> Boolean = { true },
    ): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return regex.findAll(json).map { unescape(it.groupValues[1]) }.firstOrNull(predicate)
    }

    private fun numberField(json: String, key: String): Long? {
        val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\")
}
