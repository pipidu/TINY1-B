package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GithubReleaseTest {
    @Test
    fun versionCompareTreatsPatchBumpsAsNewer() {
        assertTrue(AppVersion.isNewer("1.0.1", "1.0.0"))
        assertTrue(AppVersion.isNewer("v1.0.2", "1.0.1"))
        assertTrue(AppVersion.isNewer("1.0.3", "1.0.2"))
        assertFalse(AppVersion.isNewer("1.0.2", "1.0.2"))
        assertFalse(AppVersion.isNewer("1.0.0", "1.0.0"))
        assertFalse(AppVersion.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun parsesGithubLatestPayloadForApkAsset() {
        val json = """
            {
              "tag_name": "1.0.1",
              "name": "1.0.1",
              "body": "denoise + updater",
              "assets": [
                {
                  "name": "TINY1-B-1.0.1.apk",
                  "size": 4096,
                  "browser_download_url": "https://github.com/pipidu/TINY1-B/releases/download/1.0.1/TINY1-B-1.0.1.apk"
                }
              ]
            }
        """.trimIndent()
        val release = GithubReleaseParser.parseLatest(json)!!
        assertEquals("1.0.1", release.version)
        assertEquals("TINY1-B-1.0.1.apk", release.apkName)
        assertTrue(release.apkUrl.endsWith(".apk"))
        assertEquals(4096L, release.sizeBytes)
    }

    @Test
    fun parsesRealGithubPayloadEvenWhenLoginContainsBrackets() {
        val json = javaClass.classLoader.getResource("github-latest-1.0.1.json")!!.readText()
        assertTrue(json.contains("cursor[bot]"), "fixture must include the bot login that broke the old parser")
        val naiveEnd = json.indexOf(']', json.indexOf("\"assets\""))
        val naive = json.substring(json.indexOf('[', json.indexOf("\"assets\"")), naiveEnd + 1)
        assertFalse(naive.contains("browser_download_url"), "naive first-] cut must miss the APK url")

        val release = GithubReleaseParser.parseLatest(json)
        assertNotNull(release)
        assertEquals("1.0.1", release!!.version)
        assertEquals("TINY1-B-1.0.1.apk", release.apkName)
        assertTrue(release.apkUrl.contains("TINY1-B-1.0.1.apk"))
        assertTrue(release.sizeBytes > 1_000_000)
    }

    @Test
    fun extractJsonArraySkipsBracketsInsideStrings() {
        val json = """{"assets":[{"login":"cursor[bot]","name":"TINY1-B-1.0.2.apk"}]}"""
        val block = GithubReleaseParser.extractJsonArray(json, "assets")!!
        assertTrue(block.contains("TINY1-B-1.0.2.apk"))
        assertTrue(block.endsWith("]"))
    }
}
