package www.xdyl.hygge.com

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import java.io.File

/**
 * 【音频播放器模块】
 *
 * 基于 MediaPlayer，支持本地文件与网络 URL 播放。
 *
 * 能力：
 *  - play / toggle / stop / seekTo / release
 *  - 进度回调（每 500ms 一次，主线程）
 *  - 状态回调：playing / paused / stopped / completed / error
 *  - 可选绑定 SeekBar（拖动跳转）
 *
 * 用法：
 * ```
 * AudioPlayer.onState = { st -> tvStatus.text = st }
 * AudioPlayer.onProgress = { cur, total -> tvTime.text = "${cur/1000}s / ${total/1000}s" }
 * AudioPlayer.bindSeekBar(seekBar)
 *
 * AudioPlayer.play(this, "https://xxx/a.mp3")   // 或本地路径
 * AudioPlayer.toggle()                          // 播放/暂停
 * AudioPlayer.seekTo(30_000)
 * AudioPlayer.release()                         // 页面销毁时调用
 * ```
 */
object AudioPlayer {

    private var player: MediaPlayer? = null
    private var currentSource: String = ""
    private val handler = Handler(Looper.getMainLooper())

    var isPlaying: Boolean = false
    var durationMs: Int = 0
    var currentPositionMs: Int = 0

    /** 进度回调（主线程）：current, total */
    var onProgress: ((Int, Int) -> Unit)? = null

    /** 状态回调："playing" / "paused" / "stopped" / "completed" / "error:xxx" */
    var onState: ((String) -> Unit)? = null

    /** 播放（同一源再次调用 = 播放/暂停切换） */
    fun play(context: Context, source: String) {
        if (source.isBlank()) return
        if (source == currentSource && player != null) {
            toggle()
            return
        }
        release()
        currentSource = source
        try {
            player = MediaPlayer().apply {
                setOnPreparedListener {
                    durationMs = it.duration
                    isPlaying = true
                    start()
                    onState?.invoke("playing")
                    startProgressLoop()
                }
                setOnCompletionListener {
                    isPlaying = false
                    onState?.invoke("completed")
                    handler.removeCallbacks(progressTask)
                }
                setOnErrorListener { _, what, extra ->
                    isPlaying = false
                    onState?.invoke("error:$what/$extra")
                    true
                }
                if (source.startsWith("http://") || source.startsWith("https://")) {
                    setDataSource(source)
                } else {
                    setDataSource(File(source).absolutePath)
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            onState?.invoke("error:${e.message}")
        }
    }

    /** 播放/暂停切换 */
    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            isPlaying = false
            onState?.invoke("paused")
            handler.removeCallbacks(progressTask)
        } else {
            p.start()
            isPlaying = true
            onState?.invoke("playing")
            startProgressLoop()
        }
    }

    /** 停止（保留实例） */
    fun stop() {
        handler.removeCallbacks(progressTask)
        player?.let { if (it.isPlaying) runCatching { it.stop() } }
        isPlaying = false
        onState?.invoke("stopped")
    }

    /** 跳转进度（毫秒） */
    fun seekTo(ms: Int) {
        player?.let {
            runCatching { it.seekTo(ms.coerceIn(0, durationMs)) }
            currentPositionMs = ms
        }
    }

    /** 释放资源（页面销毁时调用） */
    fun release() {
        handler.removeCallbacks(progressTask)
        player?.let { runCatching { it.release() } }
        player = null
        isPlaying = false
        durationMs = 0
        currentPositionMs = 0
        currentSource = ""
    }

    /** 可选：绑定 SeekBar（0..1000 映射到 0..duration） */
    fun bindSeekBar(seekBar: SeekBar) {
        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && durationMs > 0) {
                    seekTo(progress * durationMs / 1000)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        onProgress = { cur, total ->
            if (total > 0) seekBar.progress = cur * 1000 / total
        }
    }

    private val progressTask = object : Runnable {
        override fun run() {
            player?.let {
                if (it.isPlaying) {
                    currentPositionMs = it.currentPosition
                    onProgress?.invoke(it.currentPosition, durationMs)
                    handler.postDelayed(this, 500)
                }
            }
        }
    }

    private fun startProgressLoop() {
        handler.removeCallbacks(progressTask)
        handler.post(progressTask)
    }
}