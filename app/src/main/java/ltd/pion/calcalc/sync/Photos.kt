package ltd.pion.calcalc.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Food photos: kept small (1024 px on the long side), so they are quick to send and the model reads them fine. */
object Photos {
    private const val SIDE = 1024

    /** The camera's file next to which a new photo is taken; FileProvider shares it under «photos». */
    fun cameraFile(context: Context): File = File(context.cacheDir, "photos").apply { mkdirs() }.let { File(it, "camera.jpg") }

    suspend fun save(context: Context, uri: Uri, id: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
            var bmp = context.contentResolver.openInputStream(uri)!!.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }!!
            val scale = SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            // cameras save the picture sideways and only note the turn in EXIF
            val turn = context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees }
            if (turn != 0) m.postRotate(turn.toFloat())
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val file = File(context.filesDir, "photos").apply { mkdirs() }.let { File(it, "$id.jpg") }
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            file.absolutePath
        }.getOrNull()
    }

    fun base64(path: String): String? =
        runCatching { Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP) }.getOrNull()

    /** A small copy for the diary row. */
    fun thumbnail(path: String, px: Int): Bitmap? = runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        var sample = 1
        while (minOf(b.outWidth, b.outHeight) / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
