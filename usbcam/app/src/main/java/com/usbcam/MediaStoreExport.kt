package com.usbcam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 把 App 私有目录里的照片/视频复制一份到系统相册（MediaStore），
 * 这样微信、相册、文件管理器都能直接看到。
 *
 * Android 10（API 29）以下不做处理：文件仍保留在 App 私有目录，不申请存储权限。
 */
object MediaStoreExport {

    private const val TAG = "MediaStoreExport"

    fun export(context: Context, file: File, isVideo: Boolean): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!file.exists()) return null

        val fileName = file.name
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (isVideo) "Movies/USBCam" else "Pictures/USBCam"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        var created: Uri? = null
        try {
            val inserted = resolver.insert(collection, values) ?: return null
            created = inserted

            val out = resolver.openOutputStream(inserted)
            if (out == null) {
                resolver.delete(inserted, null, null)
                return null
            }
            out.use { os ->
                file.inputStream().use { input -> input.copyTo(os) }
            }

            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(inserted, done, null, null)
            file.delete()
            return inserted
        } catch (e: Exception) {
            Log.e(TAG, "export failed: $fileName", e)
            created?.let {
                try {
                    resolver.delete(it, null, null)
                } catch (ignored: Exception) {
                    // ignore
                }
            }
            return null
        }
    }
}
