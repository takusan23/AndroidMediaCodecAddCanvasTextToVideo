package io.github.takusan23.androidmediacodecaddcanvastexttovideo

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/** エンコードされた動画には音声がないので、音声を追加するためのクラス */
object MixingTool {

    /**
     * [videoFile]に[audioFile]の音声を追加して、[resultFile]として生成する
     */
    @SuppressLint("WrongConstant")
    suspend fun addAudioTrack(
        videoFile: File,
        audioFile: File,
        resultFile: File
    ) = withContext(Dispatchers.Default) {
        // audioFile から音声トラックを取得
        val (audioMediaExtractor, audioFormat) = MediaExtractor().let { mediaExtractor ->
            mediaExtractor.setDataSource(audioFile.path)
            val (index, format) = (0 until mediaExtractor.trackCount)
                .map { index -> index to mediaExtractor.getTrackFormat(index) }
                .first { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            mediaExtractor.selectTrack(index)
            mediaExtractor to format
        }
        // videoFile から映像トラックを取得
        val (videoMediaExtractor, videoFormat) = MediaExtractor().let { mediaExtractor ->
            mediaExtractor.setDataSource(videoFile.path)
            val (index, format) = (0 until mediaExtractor.trackCount)
                .map { index -> index to mediaExtractor.getTrackFormat(index) }
                .first { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            mediaExtractor.selectTrack(index)
            mediaExtractor to format
        }

        // 新しくコンテナファイルを作って保存する
        // 音声と映像を追加
        val mediaMuxer = MediaMuxer(resultFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val audioTrackIndex = mediaMuxer.addTrack(audioFormat)
        val videoTrackIndex = mediaMuxer.addTrack(videoFormat)
        // MediaMuxerスタート。スタート後は addTrack が呼べない
        mediaMuxer.start()

        // 音声をコンテナに追加する
        audioMediaExtractor.apply {
            val byteBuffer = ByteBuffer.allocate(1024 * 4096)
            val bufferInfo = MediaCodec.BufferInfo()
            // データが無くなるまで回す
            while (isActive) {
                // データを読み出す
                val offset = byteBuffer.arrayOffset()
                bufferInfo.size = readSampleData(byteBuffer, offset)
                // もう無い場合
                if (bufferInfo.size < 0) break
                // 書き込む
                bufferInfo.presentationTimeUs = sampleTime
                bufferInfo.flags = sampleFlags // Lintがキレるけど黙らせる
                mediaMuxer.writeSampleData(audioTrackIndex, byteBuffer, bufferInfo)
                // 次のデータに進める
                advance()
            }
            // あとしまつ
            release()
        }

        // 映像をコンテナに追加する
        videoMediaExtractor.apply {
            val byteBuffer = ByteBuffer.allocate(1024 * 4096)
            val bufferInfo = MediaCodec.BufferInfo()
            // データが無くなるまで回す
            while (isActive) {
                // データを読み出す
                val offset = byteBuffer.arrayOffset()
                bufferInfo.size = readSampleData(byteBuffer, offset)
                // もう無い場合
                if (bufferInfo.size < 0) break
                // 書き込む
                bufferInfo.presentationTimeUs = sampleTime
                bufferInfo.flags = sampleFlags // Lintがキレるけど黙らせる
                mediaMuxer.writeSampleData(videoTrackIndex, byteBuffer, bufferInfo)
                // 次のデータに進める
                advance()
            }
            // あとしまつ
            release()
        }

        // 終わり
        mediaMuxer.stop()
        mediaMuxer.release()
    }

}