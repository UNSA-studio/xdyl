package www.xdyl.hygge.com

import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DownloadManager(
    private val url: String,
    private val totalSize: Long
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun download(destFile: File, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val chunkCount = 20
        val chunkSize = totalSize / chunkCount
        val rem = totalSize % chunkCount

        val randomAccessFile = RandomAccessFile(destFile, "rw")
        randomAccessFile.setLength(totalSize)

        val progress = AtomicInteger(0)
        val downloadedBytes = AtomicLong(0)

        val jobs = mutableListOf<Job>()
        for (i in 0 until chunkCount) {
            val start = i * chunkSize
            var end = start + chunkSize - 1
            if (i == chunkCount - 1) end = totalSize - 1
            jobs.add(launch {
                downloadChunk(start, end, randomAccessFile, downloadedBytes, progress, onProgress)
            })
        }
        jobs.joinAll()
        randomAccessFile.close()
    }

    private suspend fun downloadChunk(
        start: Long,
        end: Long,
        raf: RandomAccessFile,
        downloadedBytes: AtomicLong,
        progress: AtomicInteger,
        onProgress: (Int) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw RuntimeException("Download chunk failed ${it.code}")
            val body = it.body ?: throw RuntimeException("Empty body")
            val input = body.byteStream()
            val buffer = ByteArray(8192)
            var offset = start
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                synchronized(raf) {
                    raf.seek(offset)
                    raf.write(buffer, 0, bytesRead)
                }
                offset += bytesRead
                val total = downloadedBytes.addAndGet(bytesRead.toLong())
                val pct = (total * 100 / totalSize).toInt()
                if (pct > progress.get()) {
                    progress.set(pct)
                    withContext(Dispatchers.Main) { onProgress(pct) }
                }
            }
        }
    }
}
