package org.jellyfin.mobile.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdatesTest {
    @Test
    fun `orders releases and pre-releases`() {
        assertTrue(AppVersion.compare("0.1.2", "0.1.1") > 0)
        assertTrue(AppVersion.compare("v0.2.0", "0.1.10") > 0)
        assertTrue(AppVersion.compare("0.1.0", "0.1.0-beta.7") > 0)
        assertTrue(AppVersion.compare("0.1.0-beta.10", "0.1.0-beta.7") > 0)
        assertEquals(0, AppVersion.compare("v0.1.2", "0.1.2"))
        assertTrue(AppVersion.compare("0.0.0-dev.1", "0.1.1") < 0)
    }

    @Test
    fun `reads a GitHub release and prefers its APK asset`() {
        val body = """{"tag_name":"v0.1.3","html_url":"https://github.com/sommepo/zerifin/releases/tag/v0.1.3",
            "assets":[{"name":"SHA256SUMS.txt","browser_download_url":"https://x/sums"},
            {"name":"Zerifin-v0.1.3.apk","browser_download_url":"https://x/Zerifin-v0.1.3.apk"}]}"""

        val update = UpdateFeed.parse(UpdatePreferences.GITHUB_LATEST_RELEASE, body)

        assertEquals(AvailableUpdate("0.1.3", "https://x/Zerifin-v0.1.3.apk"), update)
    }

    @Test
    fun `reads the homepage downloads index and resolves its relative link`() {
        val body = """{"items":[{"id":"other","version":"v9"},
            {"id":"zerifin-android","version":"v0.1.4","url":"/downloads/data/Zerifin-android.apk.bin"}]}"""

        val update = UpdateFeed.parse("https://ser9.example:8789/downloads/data/index.json", body)

        assertEquals("0.1.4", update?.version)
        assertEquals("https://ser9.example:8789/downloads/data/Zerifin-android.apk.bin", update?.downloadUrl)
        assertNull(UpdateFeed.parse("https://ser9.example/index.json", """{"items":[]}"""))
    }
}
