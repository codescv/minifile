package com.minifile.explorer.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {

    @Test
    fun parsesLatestIgnoresUnknownKeysEnforcesAllowListAndPrefersReleaseApk() {
        val raw = """
            {
              "tag_name": "v1.2.0",
              "name": "MiniFile v1.2.0",
              "body": "• Faster FTP",
              "prerelease": false,
              "draft": false,
              "extra_unknown_field": {"nested": [1, 2, 3]},
              "assets": [
                {
                  "name": "minifile-v1.2.0-release.apk",
                  "browser_download_url": "http://github.com/codescv/minifile/releases/download/v1.2.0/minifile-v1.2.0-release.apk",
                  "size": 100
                },
                {
                  "name": "minifile-v1.2.0-release.apk",
                  "browser_download_url": "https://evil.example.com/codescv/minifile/releases/download/v1.2.0/minifile-v1.2.0-release.apk",
                  "size": 150
                },
                {
                  "name": "other-debug.apk",
                  "browser_download_url": "https://github.com/codescv/minifile/releases/download/v1.2.0/other-debug.apk",
                  "size": 200
                },
                {
                  "name": "minifile-v1.2.0-release.apk",
                  "browser_download_url": "https://github.com/codescv/minifile/releases/download/v1.2.0/minifile-v1.2.0-release.apk",
                  "size": 300
                },
                {
                  "name": "minifile-companion-v1.2.0.zip",
                  "browser_download_url": "https://github.com/codescv/minifile/releases/download/v1.2.0/minifile-companion-v1.2.0.zip",
                  "size": 50
                },
                {
                  "name": "SHA256SUMS.txt",
                  "browser_download_url": "https://github.com/codescv/minifile/releases/download/v1.2.0/SHA256SUMS.txt",
                  "size": 128
                }
              ]
            }
        """.trimIndent()

        val release = ReleaseParser.parseLatest(raw)
        assertEquals("v1.2.0", release.tagName)
        assertEquals("1.2.0", release.cleanVersion)

        val apk = ReleaseParser.pickApk(release)
        assertNotNull(apk)
        assertEquals("minifile-v1.2.0-release.apk", apk!!.name)
        assertEquals(300L, apk.size)

        val comp = ReleaseParser.pickCompanionZip(release)
        assertNotNull(comp)
        assertEquals("minifile-companion-v1.2.0.zip", comp!!.name)

        val sums = ReleaseParser.pickChecksums(release)
        assertNotNull(sums)
        assertEquals("SHA256SUMS.txt", sums!!.name)
    }

    @Test
    fun parseListFiltersDraftsAndPreReleasesUnlessRequested() {
        val listJson = """
            [
              {"tag_name": "v2.0.0-draft", "draft": true, "prerelease": false, "assets": []},
              {"tag_name": "v1.3.0-beta.1", "draft": false, "prerelease": true, "assets": []},
              {"tag_name": "v1.2.1", "draft": false, "prerelease": false, "assets": []}
            ]
        """.trimIndent()

        val stable = ReleaseParser.parseList(listJson, includePre = false)
        assertNotNull(stable)
        assertEquals("v1.2.1", stable!!.tagName)

        val withPre = ReleaseParser.parseList(listJson, includePre = true)
        assertNotNull(withPre)
        assertEquals("v1.3.0-beta.1", withPre!!.tagName)

        assertNull(ReleaseParser.parseList("""[{"tag_name":"v1","draft":true}]""", includePre = true))
    }
}
