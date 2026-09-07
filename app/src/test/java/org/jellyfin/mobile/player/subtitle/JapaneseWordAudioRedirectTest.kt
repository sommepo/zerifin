package org.jellyfin.mobile.player.subtitle

import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JapaneseWordAudioRedirectTest {
    @Test
    fun `pronunciation client follows the endpoint redirect to actual MPEG data`() {
        val frame = byteArrayOf(0xff.toByte(), 0xf3.toByte(), 0x84.toByte(), 0xc4.toByte()) + ByteArray(256)
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val serving = executor.submit {
                    repeat(2) { requestNumber ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            var line = reader.readLine()
                            while (!line.isNullOrEmpty()) line = reader.readLine()
                            val output = socket.getOutputStream()
                            if (requestNumber == 0) {
                                output.write("HTTP/1.1 302 Found\r\nLocation: /clip.mp3\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${frame.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                output.write(frame)
                            }
                            output.flush()
                        }
                    }
                }
                val client = JapaneseWordAudioRequest.client()
                assertFalse(client.followSslRedirects)
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/dictionary").build())
                    .execute().use { response ->
                        assertTrue(response.isSuccessful)
                        val bytes = response.body!!.bytes()
                        assertArrayEquals(frame, bytes)
                        assertTrue(JapaneseWordAudioRequest.isValid(bytes))
                    }
                serving.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
