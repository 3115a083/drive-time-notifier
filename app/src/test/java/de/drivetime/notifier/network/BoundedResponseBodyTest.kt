package de.drivetime.notifier.network

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedResponseBodyTest {
    @Test
    fun readsResponseSmallerThanLimitWithoutEof() {
        val expected = "{\"elements\":[]}".toByteArray()

        val actual = expected.toResponseBody().use { it.readBytesLimited(65_536L) }

        assertArrayEquals(expected, actual)
    }

    @Test
    fun rejectsResponseLargerThanLimit() {
        val body = ByteArray(33) { 1 }.toResponseBody()

        assertThrows(IllegalStateException::class.java) {
            body.use { it.readBytesLimited(32L) }
        }
    }
}
