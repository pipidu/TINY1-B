package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GithubDownloadMirrorTest {
    @Test
    fun prefixesCompleteHttpsGithubReleaseUrl() {
        val src = "https://github.com/pipidu/TINY1-B/releases/download/1.0.19/TINY1-B-1.0.19.apk"
        assertEquals(
            "https://gh.4o.pw/https://github.com/pipidu/TINY1-B/releases/download/1.0.19/TINY1-B-1.0.19.apk",
            GithubDownloadMirror.rewrite(src, enabled = true),
        )
    }

    @Test
    fun matchesDocsCliReleaseExample() {
        val src = "https://github.com/cli/cli/releases/download/v2.62.0/gh_2.62.0_linux_amd64.tar.gz"
        assertEquals(
            "https://gh.4o.pw/https://github.com/cli/cli/releases/download/v2.62.0/gh_2.62.0_linux_amd64.tar.gz",
            GithubDownloadMirror.rewrite(src, enabled = true),
        )
    }

    @Test
    fun prefixesGithubusercontentRedirectHost() {
        val src = "https://release-assets.githubusercontent.com/github-production-release-asset/1/TINY1-B.apk?X-Amz-Signature=ab"
        assertEquals(
            GithubDownloadMirror.PREFIX + src,
            GithubDownloadMirror.rewrite(src, enabled = true),
        )
        assertTrue(GithubDownloadMirror.isAllowedHost("release-assets.githubusercontent.com"))
        assertTrue(GithubDownloadMirror.isAllowedHost("objects.githubusercontent.com"))
    }

    @Test
    fun disabledLeavesUrlUnchanged() {
        val src = "https://github.com/pipidu/TINY1-B/releases/download/1.0.19/TINY1-B-1.0.19.apk"
        assertEquals(src, GithubDownloadMirror.rewrite(src, enabled = false))
    }

    @Test
    fun doesNotDoublePrefix() {
        val mirrored =
            "https://gh.4o.pw/https://github.com/pipidu/TINY1-B/releases/download/1.0.19/TINY1-B-1.0.19.apk"
        assertEquals(mirrored, GithubDownloadMirror.rewrite(mirrored, enabled = true))
        assertTrue(GithubDownloadMirror.isMirrored(mirrored))
    }

    @Test
    fun refusesNonGithubHost() {
        assertThrows(IllegalStateException::class.java) {
            GithubDownloadMirror.rewrite("https://example.com/file.apk", enabled = true)
        }
        assertFalse(GithubDownloadMirror.isAllowedHost("example.com"))
    }

    @Test
    fun refusesHttp() {
        assertThrows(IllegalStateException::class.java) {
            GithubDownloadMirror.rewrite(
                "http://github.com/pipidu/TINY1-B/releases/download/1.0.19/TINY1-B-1.0.19.apk",
                enabled = true,
            )
        }
    }

    @Test
    fun javaUrlKeepsInnerGithubUrlInPath() {
        val mirrored = GithubDownloadMirror.rewrite(
            "https://github.com/cli/cli/releases/download/v2.62.0/gh_2.62.0_linux_amd64.tar.gz",
            enabled = true,
        )
        val parsed = java.net.URI(mirrored).toURL()
        assertEquals("gh.4o.pw", parsed.host)
        assertEquals(
            "/https://github.com/cli/cli/releases/download/v2.62.0/gh_2.62.0_linux_amd64.tar.gz",
            parsed.path,
        )
    }
}
