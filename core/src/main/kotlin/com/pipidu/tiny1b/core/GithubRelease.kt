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
        val assets = extractJsonArray(json, "assets") ?: return null
        val apkName = stringField(assets, "name") { it.endsWith(".apk", ignoreCase = true) }
            ?: return null
        val apkUrl = stringField(assets, "browser_download_url") { url ->
            url.contains(".apk", ignoreCase = true)
        } ?: return null
        val size = numberFieldNear(assets, "size", apkName) ?: numberField(assets, "size") ?: 0L
        return GithubRelease(
            tagName = tag,
            name = name,
            body = body.replace("\\n", "\n").replace("\\r", ""),
            apkUrl = apkUrl,
            apkName = apkName,
            sizeBytes = size,
        )
    }

    /**
     * Extract a top-level JSON array by key, skipping brackets that appear inside
     * strings (GitHub logins like `cursor[bot]` used to truncate the assets array).
     */
    internal fun extractJsonArray(json: String, key: String): String? {
        val header = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\[")
        val match = header.find(json) ?: return null
        val start = match.range.last
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until json.length) {
            val c = json[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun stringField(
        json: String,
        key: String,
        predicate: (String) -> Boolean = { true },
    ): String? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return regex.findAll(json).map { unescape(it.groupValues[1]) }.firstOrNull(predicate)
    }

    private fun numberField(json: String, key: String): Long? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun numberFieldNear(json: String, key: String, nearby: String): Long? {
        val idx = json.indexOf(nearby)
        if (idx < 0) return null
        val window = json.substring(idx, (idx + 800).coerceAtMost(json.length))
        return numberField(window, key)
    }

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\")
}
