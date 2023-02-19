package io.github.takusan23.androidmediacodecaddcanvastexttovideo

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 端末の動画フォルダに保存する */
object MediaStoreTool {

    /** [videoFile]を MediaStore に登録して、ギャラリーから参照できるようにする */
    suspend fun addVideo(
        context: Context,
        videoFile: File
    ) = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val contentValues = contentValuesOf(
            MediaStore.MediaColumns.DISPLAY_NAME to videoFile.name,
            // RELATIVE_PATH（ディレクトリを掘る） は Android 10 以降のみです
            MediaStore.MediaColumns.RELATIVE_PATH to "${Environment.DIRECTORY_MOVIES}/AndroidMediaCodecAddCanvasTextToVideo"
        )
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return@withContext
        // コピーする
        contentResolver.openOutputStream(uri)?.use { outputStream ->
            videoFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

}