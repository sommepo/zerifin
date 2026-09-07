package org.jellyfin.mobile.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class YouTubeInputTest {
    @Test
    fun `opens video links and shared text`() {
        listOf(
            "nS4m2eXb4nU", "https://youtu.be/nS4m2eXb4nU?t=12",
            "https://www.youtube.com/watch?v=nS4m2eXb4nU&list=example",
            "Watch this! https://m.youtube.com/shorts/nS4m2eXb4nU",
            "https://youtube.com/live/nS4m2eXb4nU",
        ).forEach { assertEquals("nS4m2eXb4nU", YouTubeInput.videoId(it)) }
    }

    @Test
    fun `rejects other hosts credentials and playlists`() {
        listOf(
            "https://youtube.com.evil.test/watch?v=nS4m2eXb4nU",
            "https://127.0.0.1/watch?v=nS4m2eXb4nU", "https://youtube.com/playlist?list=example",
            "https://user@youtube.com/watch?v=nS4m2eXb4nU", "https://youtube.com:8765/watch?v=nS4m2eXb4nU",
            "日本語 lesson", "https://youtube.com/watch?v=short",
        ).forEach { assertNull(YouTubeInput.videoId(it)) }
    }
}
