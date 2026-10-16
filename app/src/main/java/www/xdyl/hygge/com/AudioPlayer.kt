package www.xdyl.hygge.com

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * 【音频播放器模块】
 *
 * 内核：AndroidX Media3 **ExoPlayer**（Google 官方开源播放器内核）
 *  - 不依赖系统 MediaPlayer 的厂商裁剪实现，格式支持一致且更全
 *  - 支持本地文件与网络地址（含 HLS / 渐进式 mp3 等）
 *
 * 对外 API（与页面解耦）：
 * ```
 * AudioPlayer.onState = { st -> ... }        // ready/loading/playing/paused/stopped/completed/error
 * AudioPlayer.onProgress = { cur, total -> ... }
 * AudioPlayer.bindSeekBar(seekBar)
 *
 * AudioPlayer.play(context, pathOrUrl)
 * AudioPlayer.toggle()     // 播放 / 暂停
 * AudioPlayer.stop()
 * AudioPlayer.seekTo(ms)
 * AudioPlayer.release()    // 页面销毁
 * ```
 */
object AudioPlayer {

    /** 支持的音频后缀（供文件选择器过滤使用） */
    val SUPPORTED_EXTENSIONS = arrayOf(
        "mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "m4b", "aac", "mp4", "amr", "3gp"
    )

    private var player: ExoPlayer? = null
    private var currentSource: String = ""
    private val handler = Handler(Looper.getMainLooper())

    var isPlaying: Boolean = false
    var durationMs: Int = 0
    var currentPositionMs: Int = 0

    /** 进度回调（主线程）：current, total */
    var onProgress: ((Int, Int) -> Unit)? = null

    /** 状态回调：ready / loading / playing / paused / stopped / completed / error:xxx */
    var onState: ((String) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    val dur = player?.duration ?: 0L
                    durationMs = if (dur > 0) dur.toInt() else 0
                    onState?.invoke(if (player?.isPlaying == true) "playing" else "ready")
                    if (player?.isPlaying == true) startProgressLoop()
                }
                Player.STATE_BUFFERING -> onState?.invoke("loading")
                Player.STATE_ENDED -> {
                    isPlaying = false
                    onState?.invoke("completed")
                    handler.removeCallbacks(progressTask)
                }
                else -> { /* STATE_IDLE：空闲 */ }
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            onState?.invoke(if (playing) "playing" else "paused")
            if (playing) startProgressLoop() else handler.removeCallbacks(progressTask)
        }

        override fun onPlayerError(error: PlaybackException) {
            isPlaying = false
            onState?.invoke("error:${error.errorCodeName}")
            handler.removeCallbacks(progressTask)
        }
    }

    /** 播放（同一源再次调用 = 播放 / 暂停切换） */
    fun play(context: Context, source: String) {
        if (source.isBlank()) return
        if (source == currentSource && player != null) {
            toggle()
            return
        }
        release()
        currentSource = source
        try {
            val p = ExoPlayer.Builder(context.applicationContext).build()
            p.addListener(listener)
            val uri = if (source.startsWith("http://") || source.startsWith("https://")) {
                android.net.Uri.parse(source)
            } else {
                android.net.Uri.fromFile(File(source))
            }
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.playWhenReady = true
            player = p
            isPlaying = true
            onState?.invoke("loading")
            startProgressLoop()
        } catch (e: Exception) {
            onState?.invoke("error:${e.message}")
        }
    }

    /** 播放 / 暂停切换 */
    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    /** 停止（保留内核实例，进度归零） */
    fun stop() {
        handler.removeCallbacks(progressTask)
        player?.let {
            it.pause()
            it.seekTo(0)
        }
        isPlaying = false
        currentPositionMs = 0
        onState?.invoke("stopped")
    }

    /** 跳转进度（毫秒） */
    fun seekTo(ms: Int) {
        player?.let {
            val target = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
            it.seekTo(target.toLong())
            currentPositionMs = target
        }
    }

    /** 释放资源（页面销毁时调用） */
    fun release() {
        handler.removeCallbacks(progressTask)
        player?.let {
            runCatching { it.removeListener(listener) }
            runCatching { it.release() }
        }
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
                    currentPositionMs = it.currentPosition.toInt()
                    onProgress?.invoke(currentPositionMs, durationMs)
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