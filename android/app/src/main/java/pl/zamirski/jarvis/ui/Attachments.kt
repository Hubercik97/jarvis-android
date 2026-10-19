package pl.zamirski.jarvis.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

sealed class PickedFile {
    data class Image(val name: String, val base64: String) : PickedFile()
    data class Text(val name: String, val content: String, val truncated: Boolean) : PickedFile()
    data class Unsupported(val reason: String) : PickedFile()
}

/** Przygotowanie zdjęć i plików do wysłania do AI. Nic nie jest zapisywane na stałe. */
object Attachments {
    private const val MAX_SIDE = 1280
    private const val MAX_TEXT = 10_000
    private const val MAX_B64 = 1_900_000

    fun imageToJpegBase64(context: Context, uri: Uri): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        bitmap?.let { encode(scaleDown(it)) }
    } catch (e: Exception) {
        null
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val f = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true)
    }

    private fun encode(bitmap: Bitmap): String? {
        for (quality in intArrayOf(82, 65, 50)) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= MAX_B64) return b64
        }
        return null
    }

    fun readPickedFile(context: Context, uri: Uri): PickedFile {
        val name = displayName(context, uri) ?: "plik"
        val type = context.contentResolver.getType(uri) ?: ""
        return when {
            type.startsWith("image/") -> imageToJpegBase64(context, uri)?.let { PickedFile.Image(name, it) }
                ?: PickedFile.Unsupported("Nie udało się odczytać obrazu.")
            type.startsWith("text/") || type == "application/json" -> try {
                val raw = context.contentResolver.openInputStream(uri)?.use { s ->
                    s.bufferedReader(Charsets.UTF_8).use { r ->
                        val buf = CharArray(MAX_TEXT + 1)
                        val n = r.read(buf)
                        if (n <= 0) "" else String(buf, 0, n)
                    }
                } ?: ""
                if (raw.isBlank()) {
                    PickedFile.Unsupported("Plik jest pusty.")
                } else {
                    PickedFile.Text(name, raw.take(MAX_TEXT), raw.length > MAX_TEXT)
                }
            } catch (e: Exception) {
                PickedFile.Unsupported("Nie udało się odczytać pliku.")
            }
            else -> PickedFile.Unsupported("Ten typ pliku nie jest jeszcze obsługiwany. Wybierz plik tekstowy lub obraz.")
        }
    }

    fun clearCameraCache(context: Context) {
        File(context.cacheDir, "camera").listFiles()?.forEach { it.delete() }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
