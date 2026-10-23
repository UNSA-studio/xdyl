package www.xdyl.hygge.com

import android.media.MediaMetadataRetriever
import java.io.File

/** 从 mp3 的 ID3 标签读取封面 / 艺术家 / 标题 */
object SongMeta {

    data class Meta(val title: String, val artist: String, val album: String, val cover: ByteArray?)

    fun read(file: File): Meta {
        var mmr: MediaMetadataRetriever? = null
        return try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val cover = try { mmr.embeddedPicture } catch (_: Exception) { null }
            Meta(title, artist, album, cover)
        } catch (e: Exception) {
            Meta("", "", "", null)
        } finally {
            try { mmr?.release() } catch (_: Exception) {
            }
        }
    }
}