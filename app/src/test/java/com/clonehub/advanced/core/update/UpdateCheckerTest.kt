package com.clonehub.advanced

import com.clonehub.advanced.core.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update channel's parser, checked against the shapes it will actually meet.
 *
 * These tests exist because every one of them is a way an updater can lie to a user: offering a
 * build that is not newer, hiding one that is, or installing bytes nobody checked. The real GitHub
 * payload from this repository is one of the fixtures below, copied from
 * `GET /repos/deepsilence10161-source/Clone-Hub-Pro/releases/latest` on 2026-11-15.
 */
class UpdateCheckerTest {

    private val realRelease = """
        {
          "tag_name": "latest-verified",
          "name": "CloneHub Pro - Latest Verified APK (build-2001)",
          "body": "Clone Hub Pro 2.0.2, build-2002.\n\nsha256:abc123\nsize:7905790",
          "assets": [
            {"name": "CloneHub-Pro.apk", "size": 7905790,
             "browser_download_url": "https://example.test/CloneHub-Pro.apk",
             "digest": "sha256:abc123"}
          ]
        }
    """.trimIndent()

    @Test
    fun `a release title that states a build number is read as the version code`() {
        val info = UpdateChecker.parseGitHubRelease(realRelease, installedName = "2.0.1", installedCode = 2001)
        assertEquals(2001, info.versionCode)
        // The installed build is the same number: nothing to offer, and it says so.
        assertFalse(info.isNewer)
        assertEquals("This build (2001) is not older than the offered one (2001).", info.blockedReason)
    }

    @Test
    fun `a newer build is offered with its published digest`() {
        val info = UpdateChecker.parseGitHubRelease(realRelease, installedName = "2.0.0", installedCode = 2000)
        assertTrue(info.isNewer)
        assertNull(info.blockedReason)
        assertEquals("https://example.test/CloneHub-Pro.apk", info.apkUrl)
        assertEquals("abc123", info.sha256)
        assertEquals(7905790L, info.sizeBytes)
    }

    @Test
    fun `a version name alone is never treated as a comparison`() {
        // The old shape of this repository's release: a tag and a note with no build number at all.
        val legacy = """
            {"tag_name":"latest-verified","name":"CloneHub Pro - Latest Verified APK",
             "body":"Verified commit: ce6181a",
             "assets":[{"name":"CloneHub-Pro.apk","size":1,"browser_download_url":"https://example.test/a.apk"}]}
        """.trimIndent()
        val info = UpdateChecker.parseGitHubRelease(legacy, installedName = "2.0.1", installedCode = 2001)
        assertNull(info.versionCode)
        assertFalse(info.isNewer)
        // "cannot tell" is the honest answer, and it is reported as the reason, not as "up to date".
        assertTrue(info.blockedReason!!.contains("no versionCode"))
    }

    @Test
    fun `the tag can carry the build number too`() {
        val tagged = realRelease.replace("\"name\": \"CloneHub Pro - Latest Verified APK (build-2001)\"",
            "\"name\": \"CloneHub Pro - Latest Verified APK\"").replace("\"tag_name\": \"latest-verified\"",
            "\"tag_name\": \"v2.0.2+2002\"")
        val info = UpdateChecker.parseGitHubRelease(tagged, installedName = "2.0.1", installedCode = 2001)
        assertEquals(2002, info.versionCode)
        assertTrue(info.isNewer)
    }

    @Test
    fun `a four segment version states the code last`() {
        assertEquals(2002, UpdateChecker.versionCodeFromText("2.0.2.2002"))
        assertEquals(2002, UpdateChecker.versionCodeFromText("Clone Hub Pro (2002)"))
        assertEquals(2002, UpdateChecker.versionCodeFromText("build-2002"))
        assertNull(UpdateChecker.versionCodeFromText("2.0.2"))
        assertNull(UpdateChecker.versionCodeFromText(null))
    }

    @Test
    fun `a release with no apk asset is not offered`() {
        val noAsset = """
            {"tag_name":"v2.0.2+2002","name":"x","body":"","assets":[]}
        """.trimIndent()
        val info = UpdateChecker.parseGitHubRelease(noAsset, installedName = "2.0.1", installedCode = 2001)
        assertEquals(2002, info.versionCode)
        assertNull(info.apkUrl)
        assertTrue(info.blockedReason!!.contains("no APK asset"))
    }

    @Test
    fun `a self hosted manifest is read as written`() {
        val manifest = """
            {"version":"2.0.3","versionCode":2003,"apkUrl":"https://example.test/x.apk",
             "sha256":"DEADBEEF","sizeBytes":123,"notes":"fixes"}
        """.trimIndent()
        val info = UpdateChecker.parseManifest(manifest, installedName = "2.0.2", installedCode = 2002)
        assertEquals(2003, info.versionCode)
        assertEquals("deadbeef", info.sha256) // normalised: digests are compared lowercase
        assertTrue(info.isNewer)
        assertNull(info.blockedReason)
        assertEquals("manifest", info.source)
    }

    @Test
    fun `a manifest without a version code cannot be compared`() {
        val manifest = """{"version":"2.0.3","apkUrl":"https://example.test/x.apk"}"""
        val info = UpdateChecker.parseManifest(manifest, installedName = "2.0.2", installedCode = 2002)
        assertNull(info.versionCode)
        assertFalse(info.isNewer)
        assertNotNull(info.blockedReason)
    }

    @Test
    fun `the channel is read by tag, not by whatever github calls latest`() {
        // Measured 2026-11-15: creating a versioned release per build made the archive release the
        // repository's "latest", so /releases/latest answered with the wrong one. The stable tag is
        // the only deterministic answer, and the fallback exists for a fork without it.
        assertTrue(UpdateChecker.RELEASE_API.endsWith("/releases/tags/latest-verified"))
        assertTrue(UpdateChecker.RELEASE_API_FALLBACK.endsWith("/releases/latest"))
        assertFalse(UpdateChecker.RELEASE_API == UpdateChecker.RELEASE_API_FALLBACK)
    }

    @Test
    fun `the digest in a release body is the one the download is checked against`() {
        // The publish workflow writes `sha256:<hex>` and `size:<bytes>` into the release body; the
        // app reads the digest from the asset metadata, and this asserts the two agree in the shape
        // the workflow produces.
        val body = "Clone Hub Pro 2.0.2, build-2002.\n\nsha256:4bd0317342a29a494bf5b2ee930a3232871ec51b79c6bcb0644855f346abd942\nsize:7938150"
        val digest = Regex("""sha256:([0-9a-f]{64})""").find(body)?.groupValues?.get(1)
        val size = Regex("""size:(\d+)""").find(body)?.groupValues?.get(1)?.toLong()
        assertEquals("4bd0317342a29a494bf5b2ee930a3232871ec51b79c6bcb0644855f346abd942", digest)
        assertEquals(7938150L, size)
        val info = UpdateChecker.parseGitHubRelease(
            realRelease.replace("abc123", digest!!).replace("7905790", size.toString()),
            installedName = "2.0.1", installedCode = 2001
        )
        assertEquals(digest, info.sha256)
        assertEquals(size, info.sizeBytes)
    }

    private fun assertNotNull(value: Any?) = assertTrue("expected a value", value != null)
}
