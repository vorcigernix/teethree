package com.example.teethree.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class SharedUploadFile(
    val file: File,
    val mimeType: String,
    val name: String,
    val temporary: Boolean,
)

internal fun imageDecodeSample(width: Int, height: Int, maxPixels: Long = 4_000_000): Int {
    require(width > 0 && height > 0 && maxPixels > 0) { "The image dimensions are invalid." }
    var sample = 1
    while (
        (width.toLong() + sample - 1) / sample * ((height.toLong() + sample - 1) / sample) >
            maxPixels
    ) {
        if (sample > Int.MAX_VALUE / 2) return Int.MAX_VALUE
        sample *= 2
    }
    return sample
}

internal fun fittedImageSize(width: Int, height: Int, maxEdge: Int = 2000): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0)
    val factor = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
    return (width * factor).roundToInt().coerceIn(1, maxEdge) to
        (height * factor).roundToInt().coerceIn(1, maxEdge)
}

internal fun prepareSharedImage(
    source: File,
    mimeType: String,
    name: String,
    cache: File,
): SharedUploadFile {
    val mime = mimeType.lowercase().let { if (it == "image/jpg") "image/jpeg" else it }
    if (mime !in setOf("image/png", "image/jpeg"))
        return SharedUploadFile(source, mimeType, name, false)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
        "$name could not be decoded as an image."
    }
    if (source.length() <= 10L * 1024 * 1024 && maxOf(bounds.outWidth, bounds.outHeight) <= 2000)
        return SharedUploadFile(source, mime, name, false)
    var image =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            val dimensions = fittedImageSize(info.size.width, info.size.height)
            decoder.setTargetSize(dimensions.first, dimensions.second)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    val output =
        try {
            File.createTempFile("share-image-", if (mime == "image/png") ".png" else ".jpg", cache)
        } catch (failure: Throwable) {
            image.recycle()
            throw failure
        }
    try {
        repeat(4) { iteration ->
            output.outputStream().use {
                require(
                    image.compress(
                        if (mime == "image/png") Bitmap.CompressFormat.PNG
                        else Bitmap.CompressFormat.JPEG,
                        85,
                        it,
                    )
                ) {
                    "Could not encode $name."
                }
            }
            if (output.length() <= 10L * 1024 * 1024)
                return SharedUploadFile(output, mime, name, true)
            require(iteration < 3) { "$name could not fit within the 10 MiB image limit." }
            val scale = sqrt((10.0 * 1024 * 1024) / output.length()) * .9
            val resized =
                Bitmap.createScaledBitmap(
                    image,
                    floor(image.width * scale).toInt().coerceAtLeast(1),
                    floor(image.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            if (resized !== image) {
                image.recycle()
                image = resized
            }
        }
        error("Could not prepare $name.")
    } catch (failure: Throwable) {
        output.delete()
        throw failure
    } finally {
        image.recycle()
    }
}
