package io.github.cosmicoverlord.boardingpass.ui.barcode

import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.PassText
import io.github.cosmicoverlord.boardingpass.ui.components.findActivity
import io.github.cosmicoverlord.boardingpass.core.tr

/**
 * White full screen with the barcode as big as possible, max brightness and the screen kept on.
 *
 * On curved panels (Motorola Edge 50 Ultra) the long edges bend away and distort the modules,
 * so the code keeps [edgeMarginMm] millimetres away from them: left/right in portrait,
 * top/bottom in landscape. The display cutout and system bars come from safe-drawing insets.
 * Taps on the screen do nothing on purpose: curved edges catch palms and gate staff touch the
 * phone, so it closes only with the button or the back gesture.
 */
@Composable
fun FullscreenBarcodeScreen(pass: BoardingPass, edgeMarginMm: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val previousBrightness = window?.attributes?.screenBrightness
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousLightBars = controller?.isAppearanceLightStatusBars
        window?.let { w ->
            w.attributes = w.attributes.also { it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        controller?.let {
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.isAppearanceLightStatusBars = true
        }
        onDispose {
            window?.let { w ->
                w.attributes = w.attributes.also {
                    it.screenBrightness = previousBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            controller?.let {
                it.show(WindowInsetsCompat.Type.systemBars())
                if (previousLightBars != null) it.isAppearanceLightStatusBars = previousLightBars
            }
        }
    }

    val edge = millimetresToDp(edgeMarginMm)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val padding = if (landscape) {
        PaddingValues(start = 8.dp, end = 8.dp, top = edge + 4.dp, bottom = edge + 4.dp)
    } else {
        PaddingValues(start = edge + 4.dp, end = edge + 4.dp, top = 8.dp, bottom = 8.dp)
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarcodeView(
                content = pass.barcode,
                format = pass.format,
                modifier = Modifier.fillMaxWidth().weight(1f),
                allowRotate = true,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        listOf(pass.flightLabel, "${pass.from} → ${pass.to}").filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            PassText.passenger(pass.passengerName).ifBlank { null },
                            pass.seat.ifBlank { null }?.let { tr("место $it", "seat $it") },
                        ).joinToString("  ·  "),
                        color = Color.DarkGray,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = tr("Закрыть", "Close"), tint = Color.Black) }
            }
        }
    }
}

/** Physical millimetres to dp using the panel's real pixel density (~444 ppi on the Edge 50 Ultra). */
@Composable
private fun millimetresToDp(mm: Int): Dp {
    if (mm <= 0) return 0.dp
    val metrics = LocalContext.current.resources.displayMetrics
    val physical = (metrics.xdpi + metrics.ydpi) / 2f
    // Some devices report nonsense here; fall back to the logical density bucket.
    val ppi = if (physical in 150f..1000f) physical else metrics.densityDpi.toFloat()
    val px = mm / 25.4f * ppi
    return with(LocalDensity.current) { px.toDp() }
}
