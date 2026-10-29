package www.xdyl.hygge.com

import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Strict Range validation. The supplied destination must be a staging file. */
class DownloadManager(
    private val url: String,
    private val totalSize: Long,
    private val threadCount: Int = 20,
    private val useRange: Boolean = true,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = OkHttpClient.Builder().dns(NetDns)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
) {
    private class RangeUnavailable : IOException("Server does not honor Range")

    suspend fun download(destFile: File, onProgress: (Int) -> Unit) {
        val last = AtomicLong(-1)
        downloadBytes(destFile) { done, total ->
            val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0L
            var old = last.get()
            while (pct > old) {
                if (last.compareAndSet(old, pct)) {
                    withContext(Dispatchers.Main) { onProgress(pct.toInt()) }
                    break
                }
                old = last.get()
            }
        }
    }

    suspend fun downloadBytes(destFile: File, onProgress: suspend (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        var complete = false
        try {
            if (useRange && threadCount > 1 && totalSize > 1) {
                try { ranged(destFile, onProgress) }
                catch (e: RangeUnavailable) {
                    coroutineContext.ensureActive()
                    single(destFile, onProgress)
                }
            } else single(destFile, onProgress)
            coroutineContext.ensureActive()
            complete = true
        } finally {
            // coroutineScope waits for every child before this file can be deleted or published.
            if (!complete) destFile.delete()
        }
    }

    /** Cancellation closes a blocked socket immediately, not only after its read timeout. */
    private suspend fun <T> execute(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use { block(it) } }
        catch (e: IOException) {
            // A socket closed by cancellation is cancellation, not a new child failure.
            coroutineContext.ensureActive()
            throw e
        } finally { cancellation.cancel() }
    }

    private fun request(range: String? = null): Request {
        val b = Request.Builder().url(url).header("Accept-Encoding", "identity")
        headers.forEach { (key, value) -> b.header(key, value) }
        if (range != null) b.header("Range", range)
        return b.build()
    }

    private suspend fun ranged(dest: File, progress: suspend (Long, Long) -> Unit) = coroutineScope {
        val count = minOf(threadCount.coerceAtLeast(1), totalSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val chunk = (totalSize + count - 1) / count
        val done = AtomicLong(0)
        RandomAccessFile(dest, "rw").use { it.setLength(totalSize) }
        (0 until count).mapNotNull { i ->
            val start = i * chunk
            if (start >= totalSize) null else async {
                val end = minOf(totalSize - 1, start + chunk - 1)
                execute(request("bytes=$start-$end")) { response ->
                    if (response.code == 200 || response.code == 416) throw RangeUnavailable()
                    if (response.code != 206) throw IOException("Range HTTP ${response.code}")
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                        .matchEntire(response.header("Content-Range").orEmpty())
                        ?: throw IOException("Missing/invalid Content-Range")
                    val values = range.groupValues.drop(1).map { it.toLong() }
                    if (values != listOf(start, end, totalSize)) throw IOException("Wrong Content-Range")
                    val body = response.body ?: throw IOException("Empty body")
                    val expected = end - start + 1
                    if (body.contentLength() >= 0 && body.contentLength() != expected) throw IOException("Wrong range length")
                    RandomAccessFile(dest, "rw").use { out ->
                        out.seek(start)
                        body.byteStream().use { input ->
                            var received = 0L
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (received + n > expected) throw IOException("Range overflow")
                                out.write(buffer, 0, n)
                                received += n
                                progress(done.addAndGet(n.toLong()), totalSize)
                            }
                            if (received != expected) throw IOException("Incomplete range: $received/$expected")
                        }
                    }
                }
            }
        }.awaitAll()
        progress(totalSize, totalSize)
    }

    private suspend fun single(dest: File, progress: suspend (Long, Long) -> Unit) {
        execute(request()) { response ->
            if (response.code != 200) throw IOException("Download HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty body")
            val expected = if (totalSize > 0) totalSize else body.contentLength()
            if (expected > 0 && body.contentLength() >= 0 && body.contentLength() != expected) throw IOException("Wrong file length")
            var received = 0L
            dest.outputStream().buffered().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (expected >= 0 && received + n > expected) throw IOException("Download overflow")
                        out.write(buffer, 0, n)
                        received += n
                        progress(received, expected)
                    }
                }
            }
            if (expected >= 0 && received != expected) throw IOException("Incomplete file: $received/$expected")
        }
    }
}
