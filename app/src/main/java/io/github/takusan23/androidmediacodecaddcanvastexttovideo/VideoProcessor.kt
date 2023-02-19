package io.github.takusan23.androidmediacodecaddcanvastexttovideo

import android.graphics.Canvas
import android.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 動画にCanvasをかさねる処理
 *
 * @param videoFile 元動画
 * @param resultFile エンコード後の動画
 * @param bitRate ビットレート
 * @param frameRate フレームレート
 * @param outputVideoWidth エンコード後の動画の幅
 * @param outputVideoHeight エンコード後の動画の高さ
 */
class VideoProcessor(
    private val videoFile: File,
    private val resultFile: File,
    private val bitRate: Int = 1_000_000,
    private val frameRate: Int = 30,
    private val outputVideoWidth: Int = 1280,
    private val outputVideoHeight: Int = 720,
) {

    /** データを取り出すやつ */
    private var mediaExtractor: MediaExtractor? = null

    /** エンコード用 [MediaCodec] */
    private var encodeMediaCodec: MediaCodec? = null

    /** デコード用 [MediaCodec] */
    private var decodeMediaCodec: MediaCodec? = null

    /** コンテナフォーマットへ格納するやつ */
    private val mediaMuxer by lazy { MediaMuxer(resultFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }

    /** OpenGL で加工する */
    private var codecInputSurface: CodecInputSurface? = null

    /**
     * エンコードを開始する
     *
     * @param onCanvasDrawRequest Canvasで描画する。timeMsは動画の時間
     */
    suspend fun encode(
        onCanvasDrawRequest: Canvas.(timeMs: Long) -> Unit,
    ) = withContext(Dispatchers.Default) {
        // 動画を取り出す
        val (mediaExtractor, index, format) = extractMedia(videoFile.path, "video/")
        this@VideoProcessor.mediaExtractor = mediaExtractor
        // 動画トラック
        mediaExtractor.selectTrack(index)
        mediaExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        // 解析結果から各パラメータを取り出す
        val videoMimeType = format.getString(MediaFormat.KEY_MIME)!!
        val videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        // 画面回転情報
        // Androidの縦動画はどうやら回転させているらしいので、回転を戻す
        // TODO KEY_ROTATION が Android 6 以降
        val hasRotation = format.getIntegerOrNull(MediaFormat.KEY_ROTATION) == 90
        // 画面回転度がある場合は width / height がそれぞれ入れ替わるので注意（一敗）
        val originVideoWidth = if (hasRotation) videoHeight else videoWidth
        val originVideoHeight = if (hasRotation) videoWidth else videoHeight

        // エンコード用（生データ -> H.264）MediaCodec
        encodeMediaCodec = MediaCodec.createEncoderByType(videoMimeType).apply {
            // エンコーダーにセットするMediaFormat
            // コーデックが指定されていればそっちを使う
            val videoMediaFormat = MediaFormat.createVideoFormat(videoMimeType, outputVideoWidth, outputVideoHeight).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            configure(videoMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        // エンコーダーのSurfaceを取得して、OpenGLを利用してCanvasを重ねます
        codecInputSurface = CodecInputSurface(
            encodeMediaCodec!!.createInputSurface(),
            TextureRenderer(
                outputVideoWidth = outputVideoWidth,
                outputVideoHeight = outputVideoHeight,
                originVideoWidth = originVideoWidth,
                originVideoHeight = originVideoHeight,
                videoRotation = if (hasRotation) 270f else 0f
            )
        )

        codecInputSurface?.makeCurrent()
        encodeMediaCodec!!.start()

        // デコード用（H.264 -> 生データ）MediaCodec
        codecInputSurface?.createRender()
        decodeMediaCodec = MediaCodec.createDecoderByType(videoMimeType).apply {
            // 画面回転データが有った場合にリセットする
            // このままだと回転されたままなので、OpenGL 側で回転させる
            // setInteger をここでやるのは良くない気がするけど面倒なので
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            configure(format, codecInputSurface!!.drawSurface, null, 0)
        }
        decodeMediaCodec?.start()

        // nonNull
        val decodeMediaCodec = decodeMediaCodec!!
        val encodeMediaCodec = encodeMediaCodec!!

        // メタデータ格納用
        val bufferInfo = MediaCodec.BufferInfo()

        var videoTrackIndex = -1

        var outputDone = false
        var inputDone = false

        while (!outputDone) {
            if (!inputDone) {

                val inputBufferId = decodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferId >= 0) {
                    val inputBuffer = decodeMediaCodec.getInputBuffer(inputBufferId)!!
                    val size = mediaExtractor.readSampleData(inputBuffer, 0)
                    if (size > 0) {
                        // デコーダーへ流す
                        // 今までの動画の分の再生位置を足しておく
                        decodeMediaCodec.queueInputBuffer(inputBufferId, 0, size, mediaExtractor.sampleTime, 0)
                        mediaExtractor.advance()
                    } else {
                        // 終了
                        decodeMediaCodec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        // 開放
                        mediaExtractor.release()
                        // 終了
                        inputDone = true
                    }
                }
            }
            var decoderOutputAvailable = true
            while (decoderOutputAvailable) {
                // Surface経由でデータを貰って保存する
                val encoderStatus = encodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (encoderStatus >= 0) {
                    val encodedData = encodeMediaCodec.getOutputBuffer(encoderStatus)!!
                    if (bufferInfo.size > 1) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            // MediaMuxer へ addTrack した後
                            mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                    }
                    outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encodeMediaCodec.releaseOutputBuffer(encoderStatus, false)
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // MediaMuxerへ映像トラックを追加するのはこのタイミングで行う
                    // このタイミングでやると固有のパラメーターがセットされたMediaFormatが手に入る(csd-0 とか)
                    // 映像がぶっ壊れている場合（緑で塗りつぶされてるとか）は多分このあたりが怪しい
                    val newFormat = encodeMediaCodec.outputFormat
                    videoTrackIndex = mediaMuxer.addTrack(newFormat)
                    mediaMuxer.start()
                }
                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                }
                // Surfaceへレンダリングする。そしてOpenGLでゴニョゴニョする
                val outputBufferId = decodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    decoderOutputAvailable = false
                } else if (outputBufferId >= 0) {
                    // 進捗
                    val doRender = bufferInfo.size != 0
                    decodeMediaCodec.releaseOutputBuffer(outputBufferId, doRender)
                    if (doRender) {
                        var errorWait = false
                        try {
                            codecInputSurface?.awaitNewImage()
                        } catch (e: Exception) {
                            errorWait = true
                        }
                        if (!errorWait) {
                            // 映像とCanvasを合成する
                            codecInputSurface?.drawImage { canvas ->
                                onCanvasDrawRequest(canvas, bufferInfo.presentationTimeUs / 1000L)
                            }
                            codecInputSurface?.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                            codecInputSurface?.swapBuffers()
                        }
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderOutputAvailable = false
                        encodeMediaCodec.signalEndOfInputStream()
                    }
                }
            }
        }

        // デコーダー終了
        decodeMediaCodec.stop()
        decodeMediaCodec.release()
        // OpenGL開放
        codecInputSurface?.release()
        // エンコーダー終了
        encodeMediaCodec.stop()
        encodeMediaCodec.release()
        // MediaMuxerも終了
        mediaMuxer.stop()
        mediaMuxer.release()
    }

    private fun MediaFormat.getIntegerOrNull(name: String): Int? {
        return if (containsKey(name)) {
            getInteger(name)
        } else null
    }

    private fun extractMedia(videoPath: String, startMimeType: String): Triple<MediaExtractor, Int, MediaFormat> {
        val mediaExtractor = MediaExtractor().apply { setDataSource(videoPath) }
        // 映像トラックとインデックス番号のPairを作って返す
        val (index, track) = (0 until mediaExtractor.trackCount)
            .map { index -> index to mediaExtractor.getTrackFormat(index) }
            .first { (_, track) -> track.getString(MediaFormat.KEY_MIME)?.startsWith(startMimeType) == true }
        return Triple(mediaExtractor, index, track)
    }

    companion object {
        /** タイムアウト */
        private const val TIMEOUT_US = 10_000L
    }
}