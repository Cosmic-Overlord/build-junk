package io.github.cosmicoverlord.boardingpass.ui.barcode

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.cosmicoverlord.boardingpass.core.model.CodeFormat
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import io.github.cosmicoverlord.boardingpass.core.tr

object BarcodeRenderer {
    /** Re-encodes the stored payload at one pixel per module; scaling happens at draw time. */
    fun render(content: String, format: CodeFormat): Bitmap? = try {
        val zf = when (format) {
            CodeFormat.AZTEC -> BarcodeFormat.AZTEC
            CodeFormat.PDF_417 -> BarcodeFormat.PDF_417
            CodeFormat.DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
            CodeFormat.CODE_128 -> BarcodeFormat.CODE_128
            CodeFormat.QR_CODE, CodeFormat.OTHER -> BarcodeFormat.QR_CODE
        }
        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to if (zf == BarcodeFormat.PDF_417) 2 else 1)
        if (zf == BarcodeFormat.QR_CODE) hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        if (content.any { it.code > 0x7F }) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val height = if (zf == BarcodeFormat.CODE_128) 60 else 0
        toBitmap(MultiFormatWriter().encode(content, zf, 0, height, hints))
    } catch (e: Exception) {
        null
    }

    private fun toBitmap(m: BitMatrix): Bitmap {
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) px[row + x] = if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun rotate90(src: Bitmap): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(90f) }, false)
}

/**
 * Draws the barcode as large as the box allows with crisp, equal-width modules.
 * Wide codes (PDF417) are turned sideways in portrait boxes to use the long side of the screen.
 */
@Composable
fun BarcodeView(
    content: String,
    format: CodeFormat,
    modifier: Modifier = Modifier,
    allowRotate: Boolean = false,
) {
    val images = remember(content, format) {
        BarcodeRenderer.render(content, format)?.let { bmp ->
            bmp.asImageBitmap() to BarcodeRenderer.rotate90(bmp).asImageBitmap()
        }
    }
    if (images == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tr("Не удалось построить код", "Couldn't draw the code"), color = Color.Black, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    Canvas(modifier) {
        val (normal, rotated) = images
        val wide = normal.width > normal.height * 1.3f
        val portrait = size.height > size.width
        val img: ImageBitmap = if (allowRotate && wide && portrait) rotated else normal
        var scale = min(size.width / img.width, size.height / img.height)
        if (scale >= 2f) scale = floor(scale)
        val w = (img.width * scale).roundToInt()
        val h = (img.height * scale).roundToInt()
        drawImage(
            image = img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.None,
        )
    }
}
