package com.allowance.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.allowance.core.receipt.FoundTotal
import com.allowance.core.receipt.ReadRow
import com.allowance.core.receipt.ReceiptText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer

/** What the reader saw: every row's text, and the total it picked (null when there is none). */
class ScanResult(val rows: List<ReadRow>, val total: FoundTotal?)

/**
 * Reads the total off a receipt photo, on this phone. No server, no API key, no quota. The same model and the same steps as the web app:
 * the photo is drawn [ReceiptText.WIDTH] pixels wide in grey, cut into rows of text, each row read by the ONNX model
 * (assets/receipt-reader.onnx, trained in PyTorch), and the TOTAL row picked by [ReceiptText.findTotal].
 * Nothing here saves anything: the caller shows the amount for the person to check.
 */
class ReceiptScanner(private val context: Context) {
    private val lock = Mutex()
    private var session: OrtSession? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }

    private fun session(): OrtSession = session ?: env.createSession(
        context.assets.open("receipt-reader.onnx").use { it.readBytes() },
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) },
    ).also { session = it }

    /** A receipt photo → the rows and the total. [onProgress] gets "Reading row 3 of 20…". */
    suspend fun scan(photo: Bitmap, onProgress: (String) -> Unit = {}): ScanResult = withContext(Dispatchers.Default) {
        lock.withLock {
            val w = ReceiptText.WIDTH
            val (gray, h) = grey(photo, w)
            val s = session()
            val boxes = ReceiptText.cutRows(gray, w, h)
            val rows = boxes.mapIndexed { i, box ->
                onProgress("Reading row ${i + 1} of ${boxes.size}…")
                val row = ReceiptText.rowTensor(gray, w, box)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(row.data), longArrayOf(1, 1, ReceiptText.H.toLong(), row.width.toLong())).use { input ->
                    s.run(mapOf("rows" to input)).use { out ->
                        val scores = out.get("scores").get() as OnnxTensor
                        val shape = scores.info.shape
                        val buf = scores.floatBuffer; val values = FloatArray(buf.remaining()); buf.get(values)
                        val (text, conf) = ReceiptText.decode(values, shape[1].toInt(), shape[2].toInt())
                        ReadRow(text, conf, box)
                    }
                }
            }
            ScanResult(rows, ReceiptText.findTotal(rows))
        }
    }

    companion object {
        /** The photo drawn [width] pixels wide on white, in grey 0..255 (the same weights as the web: 0.299 R + 0.587 G + 0.114 B). */
        fun grey(photo: Bitmap, width: Int): Pair<IntArray, Int> {
            val h = maxOf(1, Math.round(photo.height.toDouble() * width / photo.width).toInt())
            // Halve first while the photo is much bigger, so the last resize averages pixels instead of skipping them (like a browser's high-quality resize).
            var src = photo
            while (src.width >= width * 2) {
                val next = Bitmap.createScaledBitmap(src, src.width / 2, maxOf(1, src.height / 2), true)
                if (src !== photo) src.recycle()
                src = next
            }
            val out = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(src, width, h, true)
            Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
            if (scaled !== src && scaled !== photo) scaled.recycle()
            if (src !== photo) src.recycle()
            val px = IntArray(width * h); out.getPixels(px, 0, width, 0, 0, width, h); out.recycle()
            val gray = IntArray(px.size) { i -> val c = px[i]; Math.round(0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt() }
            return gray to h
        }

        /** Opens a photo the camera or the picker gave, turned the right way up where the phone allows, at most [maxSide] pixels on its long side. */
        fun load(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val k = minOf(1.0, maxSide.toDouble() / maxOf(info.size.width, info.size.height))
                    if (k < 1) decoder.setTargetSize(maxOf(1, (info.size.width * k).toInt()), maxOf(1, (info.size.height * k).toInt()))
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("Couldn’t open that photo.")
        }

        /** The photo shrunk to at most 1280 px on its long side, as base64 JPEG: vision models read that fine, and it is quick to send. */
        fun base64Jpeg(photo: Bitmap, max: Int = 1280): String {
            val k = minOf(1.0, max.toDouble() / maxOf(photo.width, photo.height))
            val small = if (k < 1) Bitmap.createScaledBitmap(photo, maxOf(1, (photo.width * k).toInt()), maxOf(1, (photo.height * k).toInt()), true) else photo
            val bytes = ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
            if (small !== photo) small.recycle()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}
