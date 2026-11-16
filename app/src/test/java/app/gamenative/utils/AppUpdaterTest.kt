package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun isNewer_higherPatch_returnsTrue() {
        assertTrue(AppUpdater.isNewer("v2.1.3", "2.1.2"))
    }

    @Test
    fun isNewer_higherMinor_returnsTrue() {
        assertTrue(AppUpdater.isNewer("2.2.0", "2.1.2"))
    }

    @Test
    fun isNewer_higherMajor_returnsTrue() {
        assertTrue(AppUpdater.isNewer("v3.0.0", "2.1.2"))
    }

    @Test
    fun isNewer_sameVersion_returnsFalse() {
        assertFalse(AppUpdater.isNewer("v2.1.2", "2.1.2"))
        assertFalse(AppUpdater.isNewer("2.1.2", "2.1.2"))
    }

    @Test
    fun isNewer_olderVersion_returnsFalse() {
        assertFalse(AppUpdater.isNewer("v2.1.1", "2.1.2"))
        assertFalse(AppUpdater.isNewer("1.9.9", "2.1.2"))
    }

    @Test
    fun isNewer_extraSegments_comparesCorrectly() {
        assertTrue(AppUpdater.isNewer("v2.1.2.1", "2.1.2"))
        assertFalse(AppUpdater.isNewer("2.1", "2.1.2"))
    }

    @Test
    fun isNewer_handlesPrereleaseSuffix() {
        assertFalse(AppUpdater.isNewer("v2.1.2-beta1", "2.1.2"))
        assertTrue(AppUpdater.isNewer("v2.1.3-rc1", "2.1.2"))
    }

    @Test
    fun parseRelease_validNewReleaseWithApk_parsesSuccessfully() {
        val json = """
            {
                "tag_name": "v2.1.3",
                "name": "WoW Forever v2.1.3",
                "body": "Bug fixes and improvements",
                "assets": [
                    {
                        "name": "WoW-Forever.apk",
                        "browser_download_url": "https://github.com/saaxaa/wow-forever-android-mali/releases/download/v2.1.3/WoW-Forever.apk",
                        "size": 160000000
                    }
                ]
            }
        """.trimIndent()

        val release = AppUpdater.findUpdate("[$json]", "2.1.2", "WoW-Forever.apk")

        assertNotNull(release)
        assertEquals("2.1.3", release?.version)
        assertEquals("WoW Forever v2.1.3", release?.name)
        assertEquals("Bug fixes and improvements", release?.notes)
        assertEquals("https://github.com/saaxaa/wow-forever-android-mali/releases/download/v2.1.3/WoW-Forever.apk", release?.downloadUrl)
        assertEquals(160000000L, release?.sizeBytes)
    }

    @Test
    fun parseRelease_sameOrOlderVersion_returnsNull() {
        val json = """
            {
                "tag_name": "v2.1.2",
                "name": "WoW Forever v2.1.2",
                "body": "Existing release",
                "assets": [
                    {
                        "name": "WoW-Forever.apk",
                        "browser_download_url": "https://github.com/saaxaa/wow-forever-android-mali/releases/download/v2.1.2/WoW-Forever.apk",
                        "size": 160000000
                    }
                ]
            }
        """.trimIndent()

        assertNull(AppUpdater.findUpdate("[$json]", "2.1.2", "WoW-Forever.apk"))
    }

    @Test
    fun parseRelease_noApkAsset_returnsNull() {
        val json = """
            {
                "tag_name": "v2.1.3",
                "name": "WoW Forever v2.1.3",
                "body": "Source only release",
                "assets": [
                    {
                        "name": "source.tar.gz",
                        "browser_download_url": "https://example.com/source.tar.gz",
                        "size": 5000
                    }
                ]
            }
        """.trimIndent()

        assertNull(AppUpdater.findUpdate("[$json]", "2.1.2", "WoW-Forever.apk"))
    }

    @Test
    fun emptyFeedOffersNoUpdate() {
        assertNull(AppUpdater.findUpdate("[]", "2.1.2", AppUpdater.MALI_APK))
    }

    @Test
    fun forkPrereleaseChannelMatchesPackageAndComparesRevision() {
        val apk = AppUpdater.MALI_APK
        fun release(tag: String, repo: String = "saaxaa/wow-forever-android-mali", asset: String = apk) =
            """{"tag_name":"$tag","prerelease":true,"assets":[{"name":"$asset","browser_download_url":"https://github.com/$repo/releases/download/$tag/$asset"}]}"""
        val tag = "v2.4.3-mali.10"
        val feed = listOf(release("v2.4.3-mali.2"), release(tag), release("v3.0.0-mali.1", "jaredgei/wow-forever-android"),
            release("v3.1.0-mali.1", asset = "WoW-Forever.apk"), release("v2.4.4-login1")).joinToString(",", "[", "]")
        assertEquals(tag.drop(1), AppUpdater.findUpdate(feed, "2.4.2-mali-test", apk)?.version)
        assertEquals(tag.drop(1), AppUpdater.findUpdate(feed, "2.4.3-mali.9", apk)?.version)
        assertNull(AppUpdater.findUpdate(feed, tag.drop(1), apk))
    }
}
