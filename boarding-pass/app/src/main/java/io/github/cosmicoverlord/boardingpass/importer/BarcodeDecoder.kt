package io.github.cosmicoverlord.boardingpass.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import io.github.cosmicoverlord.boardingpass.core.model.CodeFormat
import io.github.cosmicoverlord.boardingpass.core.model.DecodedCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** Finds boarding-pass barcodes in pictures and PDF pages: ML Kit first, ZXing as a fallback. */
class BarcodeDecoder(private val context: Context) {
    private val scanner: BarcodeScanner by lazy { BarcodeScanning.getClient(scannerOptions()) }

    suspend fun decodeImage(uri: Uri): List<DecodedCode> {
        val bitmap = withContext(Dispatchers.IO) { loadBitmap(uri, MAX_IMAGE_SIDE) }
            ?: throw ImportException("Не удалось открыть изображение")
        return try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun decodePdf(uri: Uri): List<DecodedCode> = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw ImportException("Не удалось открыть PDF")
        val found = LinkedHashMap<String, DecodedCode>()
        try {
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                throw ImportException("PDF повреждён или защищён паролем")
            }
            try {
                for (i in 0 until minOf(renderer.pageCount, MAX_PDF_PAGES)) {
                    var codes = renderPage(renderer, i, PDF_RENDER_SIDE)
                    if (codes.isEmpty()) codes = renderPage(renderer, i, PDF_RENDER_SIDE_HQ)
                    codes.forEach { found.putIfAbsent(it.raw, it) }
                }
            } finally {
                renderer.close()
            }
        } finally {
            pfd.close()
        }
        found.values.toList()
    }

    private suspend fun renderPage(renderer: PdfRenderer, index: Int, longSide: Int): List<DecodedCode> {
        val page = renderer.openPage(index)
        val bitmap = try {
            val scale = longSide.toFloat() / max(page.width, page.height)
            val bmp = Bitmap.createBitmap(
                (page.width * scale).roundToInt().coerceAtLeast(1),
                (page.height * scale).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            bmp.eraseColor(Color.WHITE) // PdfRenderer leaves the background transparent
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            page.close()
        }
        return try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun decodeBitmap(bitmap: Bitmap): List<DecodedCode> {
        val ml = try {
            mlKit(bitmap)
        } catch (e: Exception) {
            emptyList()
        }
        if (ml.isNotEmpty()) return ml
        return withContext(Dispatchers.Default) { zxing(bitmap) }
    }

    private suspend fun mlKit(bitmap: Bitmap): List<DecodedCode> = suspendCancellableCoroutine { cont ->
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { list -> cont.resume(list.mapNotNull { it.toDecoded() }.distinctBy { it.raw }) }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    private fun zxing(source: Bitmap): List<DecodedCode> {
        val bitmap = downscale(source, ZXING_MAX_SIDE)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (bitmap !== source) bitmap.recycle()
        val luminance = RGBLuminanceSource(w, h, pixels)
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.PDF_417, BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE,
                BarcodeFormat.DATA_MATRIX, BarcodeFormat.CODE_128,
            ),
        )
        val reader = MultiFormatReader().apply { setHints(hints) }
        for (binary in listOf(BinaryBitmap(HybridBinarizer(luminance)), BinaryBitmap(GlobalHistogramBinarizer(luminance)))) {
            try {
                val results = GenericMultipleBarcodeReader(reader).decodeMultiple(binary, hints)
                val codes = results.mapNotNull { r -> r.text?.let { DecodedCode(it, r.barcodeFormat.toCodeFormat()) } }
                if (codes.isNotEmpty()) return codes.distinctBy { it.raw }
            } catch (e: ReaderException) {
                // try the next binarizer
            }
        }
        return emptyList()
    }

    private fun loadBitmap(uri: Uri, maxSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val k = maxSide.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * k).roundToInt().coerceAtLeast(1),
                        (info.size.height * k).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        null
    }

    private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val k = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * k).roundToInt(), (bitmap.height * k).roundToInt(), true)
    }

    companion object {
        private const val MAX_IMAGE_SIDE = 3000
        private const val ZXING_MAX_SIDE = 2000
        private const val MAX_PDF_PAGES = 12
        private const val PDF_RENDER_SIDE = 2400
        private const val PDF_RENDER_SIDE_HQ = 3600

        fun scannerOptions(): BarcodeScannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_CODE_128,
            )
            .build()
    }
}

class ImportException(message: String) : Exception(message)

fun Barcode.toDecoded(): DecodedCode? {
    val raw = rawValue?.takeIf { it.isNotEmpty() }
        ?: rawBytes?.takeIf { it.isNotEmpty() }?.toString(Charsets.ISO_8859_1)
        ?: return null
    val f = when (format) {
        Barcode.FORMAT_QR_CODE -> CodeFormat.QR_CODE
        Barcode.FORMAT_AZTEC -> CodeFormat.AZTEC
        Barcode.FORMAT_PDF417 -> CodeFormat.PDF_417
        Barcode.FORMAT_DATA_MATRIX -> CodeFormat.DATA_MATRIX
        Barcode.FORMAT_CODE_128 -> CodeFormat.CODE_128
        else -> CodeFormat.OTHER
    }
    return DecodedCode(raw, f)
}

private fun BarcodeFormat.toCodeFormat(): CodeFormat = when (this) {
    BarcodeFormat.QR_CODE -> CodeFormat.QR_CODE
    BarcodeFormat.AZTEC -> CodeFormat.AZTEC
    BarcodeFormat.PDF_417 -> CodeFormat.PDF_417
    BarcodeFormat.DATA_MATRIX -> CodeFormat.DATA_MATRIX
    BarcodeFormat.CODE_128 -> CodeFormat.CODE_128
    else -> CodeFormat.OTHER
}
