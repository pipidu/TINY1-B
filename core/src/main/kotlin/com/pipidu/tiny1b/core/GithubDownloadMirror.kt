package com.pipidu.tiny1b.core

import java.net.URI

/**
 * GH Proxy download rewrite ([docs](https://gh.4o.pw/docs)).
 *
 * Prefix the **complete** GitHub HTTPS file URL:
 * `https://gh.4o.pw/` + `https://github.com/owner/repo/releases/download/tag/file`
 *
 * Allowed upstream hosts: `github.com`, `*.github.com`,
 * `githubusercontent.com`, `*.githubusercontent.com`. Other hosts are refused.
 * `api.github.com` version checks stay direct; only APK / redirect hops use this.
 */
object GithubDownloadMirror {
    const val PREFIX = "https://gh.4o.pw/"
    const val DOCS_URL = "https://gh.4o.pw/docs"

    fun rewrite(url: String, enabled: Boolean): String {
        if (!enabled) return url
        if (isMirrored(url)) return url
        val host = httpsHost(url)
            ?: throw IllegalStateException("镜像只允许 HTTPS 下载地址")
        if (!isAllowedHost(host)) {
            throw IllegalStateException("镜像不支持该下载地址")
        }
        return PREFIX + url
    }

    fun isMirrored(url: String): Boolean =
        url.startsWith(PREFIX, ignoreCase = true)

    fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == "github.com" ||
            h.endsWith(".github.com") ||
            h == "githubusercontent.com" ||
            h.endsWith(".githubusercontent.com")
    }

    private fun httpsHost(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        return uri.host
    }
}
