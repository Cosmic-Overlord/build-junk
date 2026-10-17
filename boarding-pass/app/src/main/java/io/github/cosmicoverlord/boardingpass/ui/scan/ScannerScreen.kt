package io.github.cosmicoverlord.boardingpass.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import io.github.cosmicoverlord.boardingpass.core.bcbp.BcbpParser
import io.github.cosmicoverlord.boardingpass.core.model.DecodedCode
import io.github.cosmicoverlord.boardingpass.importer.BarcodeDecoder
import io.github.cosmicoverlord.boardingpass.importer.toDecoded
import io.github.cosmicoverlord.boardingpass.core.tr

@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onBoardingPass: (DecodedCode) -> Unit,
    onOtherCode: (DecodedCode) -> Unit,
    onPickImage: (android.net.Uri) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onPickImage)
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    var torch by remember { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableStateOf(DEFAULT_ZOOM) }
    var other by remember { mutableStateOf<DecodedCode?>(null) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(tr("Сканирование", "Scanning")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Назад", "Back")) }
                },
                actions = {
                    if (granted) {
                        IconButton(onClick = { torch = !torch }) {
                            Icon(if (torch) Icons.Default.FlashOff else Icons.Default.FlashOn, contentDescription = tr("Фонарик", "Flashlight"))
                        }
                    }
                    IconButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = tr("Из галереи", "From the gallery"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (granted) {
                CameraPreview(
                    torch = torch,
                    zoom = zoom,
                    onBoardingPass = onBoardingPass,
                    onOtherCode = { other = it },
                )
                ScanOverlay()
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tr("Наведите камеру на штрихкод посадочного талона", "Point the camera at the boarding pass barcode"),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        tr("Держите телефон в 20–30 см от кода, коснитесь экрана для фокуса", "Hold the phone 20–30 cm from the code, tap to focus"),
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(
                    // The Scaffold's padding already clears the navigation bar.
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZOOM_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = zoom == preset,
                                onClick = { zoom = preset },
                                label = { Text(if (preset % 1f == 0f) "${preset.toInt()}×" else "$preset×") },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Black.copy(alpha = 0.5f),
                                    labelColor = Color.White,
                                ),
                            )
                        }
                    }
                    other?.let { code ->
                        OtherCodeCard(code = code, onSave = { onOtherCode(code) }, onDismiss = { other = null })
                    }
                }
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
                    Text(
                        tr("Нужен доступ к камере, чтобы считать штрихкод. Можно также выбрать скриншот из галереи.", "Camera access is needed to read the barcode. You can also pick a screenshot from the gallery."),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(tr("Разрешить камеру", "Allow camera")) }
                    TextButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(tr("Выбрать из галереи", "Pick from the gallery"), color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    torch: Boolean,
    zoom: Float,
    onBoardingPass: (DecodedCode) -> Unit,
    onOtherCode: (DecodedCode) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build()
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }
    val currentOnPass by rememberUpdatedState(onBoardingPass)
    val currentOnOther by rememberUpdatedState(onOtherCode)
    val currentZoom by rememberUpdatedState(zoom)

    DisposableEffect(lifecycleOwner) {
        val scanner = BarcodeScanning.getClient(BarcodeDecoder.scannerOptions())
        val executor = ContextCompat.getMainExecutor(context)
        var done = false
        var lastOther: String? = null
        var otherHits = 0
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                if (done) return@MlKitAnalyzer
                val codes = result.getValue(scanner).orEmpty().mapNotNull { it.toDecoded() }
                val pass = codes.firstOrNull { BcbpParser.parseOrNull(it.raw) != null }
                if (pass != null) {
                    done = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnPass(pass)
                    return@MlKitAnalyzer
                }
                // Something that is not a boarding pass: offer it only once it is seen steadily.
                val first = codes.firstOrNull() ?: return@MlKitAnalyzer
                if (first.raw == lastOther) otherHits++ else {
                    lastOther = first.raw
                    otherHits = 1
                }
                if (otherHits == 5) currentOnOther(first)
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        // Zoom set before the camera opens is dropped, so apply the preset once the first zoom state arrives.
        var zoomApplied = false
        val zoomObserver = Observer<ZoomState> {
            if (!zoomApplied) {
                zoomApplied = true
                controller.setZoomRatio(currentZoom)
            }
        }
        controller.zoomState.observe(lifecycleOwner, zoomObserver)
        onDispose {
            controller.zoomState.removeObserver(zoomObserver)
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }
    LaunchedEffect(torch) { controller.enableTorch(torch) }
    LaunchedEffect(zoom) { controller.setZoomRatio(zoom) }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The Edge 50 Ultra's large main sensor can't focus closer than ~10 cm, so a 1.5× start lets
 * people hold the phone back far enough for small PDF417 codes to be sharp. Presets stay below
 * 3× so CameraX doesn't hand over to the telephoto lens, which focuses even farther away.
 */
private const val DEFAULT_ZOOM = 1.5f
private val ZOOM_PRESETS = listOf(1f, 1.5f, 2f)

@Composable
private fun ScanOverlay() {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        val w = size.width * 0.86f
        val h = minOf(size.height * 0.42f, w * 0.85f)
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
        val radius = CornerRadius(28.dp.toPx())
        drawRect(Color.Black.copy(alpha = 0.45f))
        drawRoundRect(Color.Transparent, topLeft, GeoSize(w, h), radius, blendMode = BlendMode.Clear)
        drawRoundRect(Color.White, topLeft, GeoSize(w, h), radius, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun OtherCodeCard(code: DecodedCode, onSave: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("Найден код, но это не посадочный IATA", "Found a code, but it is not an IATA boarding pass"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                code.raw,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(tr("Продолжить", "Keep scanning")) }
                TextButton(onClick = onSave) { Text(tr("Сохранить как есть", "Save as is")) }
            }
        }
    }
}
