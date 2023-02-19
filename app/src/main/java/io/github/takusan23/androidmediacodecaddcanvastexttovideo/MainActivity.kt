package io.github.takusan23.androidmediacodecaddcanvastexttovideo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import io.github.takusan23.androidmediacodecaddcanvastexttovideo.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private val workFolder by lazy { File(getExternalFilesDir(null), "video").apply { mkdir() } }
    private val viewBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    /** 動画ピッカー */
    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        // コピーする
        lifecycleScope.launch(Dispatchers.IO) {
            val videoFile = File(workFolder, VIDEO_FILE_NAME).apply {
                createNewFile()
            }
            videoFile.outputStream().use { outputStream ->
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        // 動画を選択する
        viewBinding.videoSelectButton.setOnClickListener {
            videoPicker.launch("video/mp4")
        }

        // エンコーダーを起動する
        viewBinding.encodeButton.setOnClickListener {
            lifecycleScope.launch {

                viewBinding.encodeStatusTextView.text = "エンコード開始"

                // まずは Canvas と映像を重ねる
                val videoFile = File(workFolder, VIDEO_FILE_NAME)
                val canvasOverlayVideoFile = File(workFolder, VIDEO_CANVAS_OVERLAY_FILE_NAME)
                val videoWidth = 1280
                val videoHeight = 720
                val videoProcessor = VideoProcessor(
                    videoFile = videoFile,
                    resultFile = canvasOverlayVideoFile,
                    outputVideoWidth = videoWidth,
                    outputVideoHeight = videoHeight
                )

                val textPaint = Paint().apply {
                    textSize = 100f
                }
                val logoBitmap = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_launcher_foreground)?.apply {
                    setTint(Color.WHITE)
                }?.toBitmap(300, 300)!!

                videoProcessor.encode { currentTimeMs ->
                    // 適当に文字を書く
                    val text = "動画の時間 = ${"%.2f".format(currentTimeMs / 1000f)}"

                    textPaint.color = Color.BLACK
                    textPaint.style = Paint.Style.STROKE
                    // 枠取り文字
                    drawText(text, 200f, 300f, textPaint)

                    textPaint.style = Paint.Style.FILL
                    textPaint.color = Color.WHITE
                    // 枠無し文字
                    drawText(text, 200f, 300f, textPaint)

                    // 画像も表示する
                    drawBitmap(logoBitmap, (videoWidth - logoBitmap.width).toFloat(), (videoHeight - logoBitmap.height).toFloat(), textPaint)
                }

                // 音声がないので元のファイルから音声だけもらってくる
                // 音声を追加したファイルが最終的なファイルになる
                val resultFile = File(workFolder, RESULT_VIDEO_FILE_NAME)
                MixingTool.addAudioTrack(
                    videoFile = canvasOverlayVideoFile,
                    audioFile = videoFile,
                    resultFile = resultFile
                )

                // 端末の動画フォルダへ転送する
                MediaStoreTool.addVideo(this@MainActivity, resultFile)

                // 転送したら要らなくなるので削除
                resultFile.delete()
                canvasOverlayVideoFile.delete()
                // videoFile.delete() // 毎回消すなら

                viewBinding.encodeStatusTextView.text = "エンコード終了"

            }
        }

    }

    companion object {
        /** かさねる動画のファイル名 */
        private const val VIDEO_FILE_NAME = "origin_video_file.mp4"

        /** Canvasと重ねた動画のファイル名 */
        private const val VIDEO_CANVAS_OVERLAY_FILE_NAME = "temp_canvas_overlay.mp4"

        /** エンコードした動画ファイル名 */
        private const val RESULT_VIDEO_FILE_NAME = "result.mp4"
    }

}