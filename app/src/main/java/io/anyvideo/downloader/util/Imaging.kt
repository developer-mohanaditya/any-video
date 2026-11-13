package io.anyvideo.downloader.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object Imaging {

    fun load(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (t: Throwable) {
        null
    }

    /** Saves to the same anyvideo folders used by downloads. */
    fun saveToGallery(context: Context, bitmap: Bitmap, fileName: String, isVideoFolder: Boolean = false): Uri? {
        val mime = "image/png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideoFolder) DownloadPathsMirror.VIDEO else DownloadPathsMirror.IMAGE,
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (isVideoFolder) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val uri = context.contentResolver.insert(collection, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        if (isVideoFolder) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
                    ),
                    "anyvideo",
                )
                dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                Uri.fromFile(file)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Writes a shareable copy into the FileProvider cache and returns its content uri. */
    fun shareableFile(context: Context, bitmap: Bitmap, fileName: String): Uri? = try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (t: Throwable) {
        null
    }

    fun shareIntent(uri: Uri, packageName: String? = null): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (packageName != null) setPackage(packageName)
        }
        return intent
    }

    fun square(bitmap: Bitmap): Bitmap {
        val size = maxOf(bitmap.width, bitmap.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val left = ((size - bitmap.width) / 2).toFloat()
        val top = ((size - bitmap.height) / 2).toFloat()
        canvas.drawBitmap(bitmap, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Blurred-background square, the "square photo without crop" tool. */
    fun squareWithBlurredBackground(bitmap: Bitmap, size: Int = 1080): Bitmap {
        val background = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val blurred = stackBlur(background, 24)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = Rect(0, 0, size, size)
        canvas.drawBitmap(blurred, null, rect, Paint())
        val scale = minOf(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val scaledW = (bitmap.width * scale).toInt()
        val scaledH = (bitmap.height * scale).toInt()
        val left = ((size - scaledW) / 2).toFloat()
        val top = ((size - scaledH) / 2).toFloat()
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + scaledW, top + scaledH),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    fun stackBlur(source: Bitmap, radius: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val clamped = radius.coerceIn(1, 40)
        for (pass in 0 until 2) {
            for (y in 0 until height) {
                var r = 0
                var g = 0
                var b = 0
                for (x in 0 until width) {
                    val index = y * width + x
                    val color = pixels[index]
                    r += (color shr 16) and 0xFF
                    g += (color shr 8) and 0xFF
                    b += color and 0xFF
                }
                val avgR = r / width
                val avgG = g / width
                val avgB = b / width
                for (x in 0 until width) {
                    val index = y * width + x
                    val color = pixels[index]
                    val factor = 1f / (clamped + 1)
                    val nr = ((((color shr 16) and 0xFF) + avgR * clamped) * factor).toInt()
                    val ng = ((((color shr 8) and 0xFF) + avgG * clamped) * factor).toInt()
                    val nb = (((color and 0xFF) + avgB * clamped) * factor).toInt()
                    pixels[index] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Splits one image into rows x columns tiles for the profile grid tool. */
    fun splitGrid(bitmap: Bitmap, rows: Int, cols: Int): List<Bitmap> {
        val tiles = ArrayList<Bitmap>()
        val tileWidth = bitmap.width / cols
        val tileHeight = bitmap.height / rows
        if (tileWidth <= 0 || tileHeight <= 0) return tiles
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x = col * tileWidth
                val y = row * tileHeight
                tiles += Bitmap.createBitmap(bitmap, x, y, tileWidth, tileHeight)
            }
        }
        return tiles
    }

    fun resize(bitmap: Bitmap, width: Int, height: Int): Bitmap =
        Bitmap.createScaledBitmap(bitmap, width.coerceAtLeast(1), height.coerceAtLeast(1), true)

    fun circleCrop(bitmap: Bitmap, size: Int = 800, borderColor: Int = Color.WHITE): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val side = minOf(bitmap.width, bitmap.height).toFloat()
        val left = (bitmap.width - side) / 2f
        val top = (bitmap.height - side) / 2f
        val src = Rect(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint().apply { color = borderColor })
        val inner = size / 2f - size * 0.04f
        canvas.drawBitmap(
            bitmap,
            src,
            RectF(size / 2f - inner, size / 2f - inner, size / 2f + inner, size / 2f + inner),
            paint,
        )
        return out
    }
}

/** Kept separate so util code does not depend on the data package. */
private object DownloadPathsMirror {
    const val IMAGE = "Pictures/anyvideo"
    const val VIDEO = "Movies/anyvideo"
}
