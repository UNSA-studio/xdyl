package www.xdyl.hygge.com

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadManagerTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val payload = ByteArray(1024) { (it % 251).toByte() }
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun responseFor(request: RecordedRequest, ignoreRange: Boolean = false, wrongRange: Boolean = false,
                            slow: Boolean = false): MockResponse {
        val range = request.getHeader("Range")
        if (range == null || ignoreRange) return MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
        val pair = range.removePrefix("bytes=").split('-').map { it.toInt() }
        val start = pair[0]
        val end = pair[1]
        val response = MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes ${if (wrongRange) 999 else start}-$end/${payload.size}")
            .setBody(Buffer().write(payload.copyOfRange(start, end + 1)))
        if (slow) response.throttleBody(1, 100, TimeUnit.MILLISECONDS)
        return response
    }

    private fun downloader() = DownloadManager(server.url("/pack").toString(), payload.size.toLong(), 4, true,
        mapOf("Authorization" to "Bearer test-token"), client)

    @Test fun validRangesProduceExactlyTheOriginalFile() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer test-token", request.getHeader("Authorization"))
                return responseFor(request)
            }
        }
        val dest = File(temp.root, "pack.part")
        downloader().downloadBytes(dest) { _, _ -> }
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun rangeIgnoredWith200FallsBackToACompleteSingleDownload() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = responseFor(request, ignoreRange = true)
        }
        val dest = File(temp.root, "pack.part")
        downloader().downloadBytes(dest) { _, _ -> }
        assertEquals(payload.size.toLong(), dest.length())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun incorrectContentRangeFailsAndDeletesPreallocatedFile() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = responseFor(request, wrongRange = true)
        }
        val dest = File(temp.root, "pack.part")
        try { downloader().downloadBytes(dest) { _, _ -> }; fail("Invalid Content-Range accepted") }
        catch (_: IOException) { }
        assertFalse(dest.exists())
    }

    @Test fun incompleteBodyIsNeverAcceptedAsACompleteDownload() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-255/1024").setBody("short")
        }
        val dest = File(temp.root, "pack.part")
        try { downloader().downloadBytes(dest) { _, _ -> }; fail("Incomplete file accepted") }
        catch (_: IOException) { }
        assertFalse(dest.exists())
    }

    @Test fun cancellationClosesAllWorkersBeforeRemovingPartialFile() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = responseFor(request, slow = true)
        }
        val dest = File(temp.root, "pack.part")
        val job = launch(Dispatchers.IO) { downloader().downloadBytes(dest) { _, _ -> } }
        withTimeout(5000) { while (server.requestCount == 0) delay(10) }
        withTimeout(5000) { job.cancelAndJoin() }
        assertFalse(dest.exists())
    }
}