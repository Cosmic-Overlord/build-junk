package io.github.cosmicoverlord.boardingpass.ui.seatmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cosmicoverlord.boardingpass.AppGraph
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.seatmap.Cabin
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMap
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapLogic
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapsUrls
import io.github.cosmicoverlord.boardingpass.core.seatmap.SmSeat
import io.github.cosmicoverlord.boardingpass.core.tr
import io.github.cosmicoverlord.boardingpass.data.SeatMapResult
import io.github.cosmicoverlord.boardingpass.ui.AppViewModel
import io.github.cosmicoverlord.boardingpass.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Full-screen cabin layout with the pass's seat highlighted. Downloads only when opened. */
@Composable
fun SeatMapScreen(vm: AppViewModel, passId: String, onBack: () -> Unit) {
    val passes by vm.passes.collectAsStateWithLifecycle()
    val pass = passes.firstOrNull { it.id == passId }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var reload by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<SeatMapResult?>(null) }
    var pickAircraft by remember { mutableStateOf(false) }

    LaunchedEffect(passId, reload) {
        val p = AppGraph.repository.get(passId) ?: return@LaunchedEffect
        state = null
        state = AppGraph.seatMaps.load(p, force)
        force = false
    }

    val ready = state as? SeatMapResult.Ready
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tr("Схема салона", "Seat map"))
                        val sub = ready?.let { r -> r.aircraft.title + (r.variants.firstOrNull { it.id == r.selected }?.let { " · ${it.name}" } ?: "") }
                        if (sub != null) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Назад", "Back")) }
                },
                actions = {
                    val web = when (val s = state) {
                        is SeatMapResult.Ready -> s.pageUrl
                        is SeatMapResult.Failed -> s.webUrl
                        is SeatMapResult.ChooseAircraft -> SeatMapsUrls.airline(s.airlineSlug)
                        null -> null
                    }
                    if (web != null) {
                        IconButton(onClick = { uri.openUri(web) }) { Icon(Icons.Default.Language, contentDescription = tr("Открыть сайт", "Open the site")) }
                    }
                    IconButton(onClick = {
                        force = true
                        reload++
                    }) { Icon(Icons.Default.Refresh, contentDescription = tr("Скачать заново", "Download again")) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                null -> Loading()
                is SeatMapResult.Failed -> Failed(s.message, onRetry = { reload++ }, onOpen = s.webUrl?.let { url -> { uri.openUri(url) } })
                is SeatMapResult.ChooseAircraft -> AircraftList(s.reason, s.fleet.map { it.slug to it.title }) { slug ->
                    scope.launch {
                        AppGraph.repository.modify(passId) { it.copy(seatMapAircraft = slug, seatMapVariant = null) }
                        reload++
                    }
                }
                is SeatMapResult.Ready -> if (pickAircraft) {
                    AircraftList(tr("Выберите самолёт", "Pick the aircraft"), s.fleet.map { it.slug to it.title }) { slug ->
                        pickAircraft = false
                        scope.launch {
                            AppGraph.repository.modify(passId) { it.copy(seatMapAircraft = slug, seatMapVariant = null) }
                            reload++
                        }
                    }
                } else {
                    ReadyContent(
                        s = s,
                        pass = pass,
                        onVariant = { id ->
                            state = s.copy(selected = id)
                            scope.launch { AppGraph.repository.modify(passId) { it.copy(seatMapVariant = id) } }
                        },
                        onOtherAircraft = { pickAircraft = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(tr("Загружаю схему с seatmaps.com…", "Loading the seat map from seatmaps.com…"))
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit, onOpen: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(tr("Повторить", "Retry")) }
        if (onOpen != null) TextButton(onClick = onOpen) { Text(tr("Открыть seatmaps.com", "Open seatmaps.com")) }
    }
}

@Composable
private fun AircraftList(reason: String, fleet: List<Pair<String, String>>, onPick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(fleet, key = { it.first }) { (slug, title) ->
            ListItem(
                headlineContent = { Text(title) },
                leadingContent = { Icon(Icons.Default.Flight, contentDescription = null) },
                modifier = Modifier.clickable { onPick(slug) },
            )
        }
    }
}

@Composable
private fun ReadyContent(s: SeatMapResult.Ready, pass: BoardingPass?, onVariant: (String) -> Unit, onOtherAircraft: () -> Unit) {
    val map = s.map
    val mySeat = pass?.seat?.trim()?.uppercase()?.ifEmpty { null }
    var selected by remember(map.id) { mutableStateOf(mySeat?.let { map.seat(it) }) }
    Column(Modifier.fillMaxSize()) {
        if (s.variants.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s.variants.forEach { v ->
                    val fits = v.id in s.candidates
                    FilterChip(
                        selected = v.id == s.selected,
                        onClick = { onVariant(v.id) },
                        label = { Text(v.name + (v.summary?.substringBefore(':')?.let { " · $it" } ?: "")) },
                        leadingIcon = if (fits) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
            val hint = when {
                s.candidates.size > 1 -> tr(
                    "Место есть в нескольких компоновках — авиакомпания не сообщает, какая будет. Отмечены подходящие.",
                    "The seat exists in several layouts and the airline doesn't say which flies. Matching ones are ticked.",
                )
                s.candidates.isEmpty() && mySeat != null -> tr(
                    "Места $mySeat нет ни в одной компоновке этого самолёта — возможно, самолёт другой.",
                    "Seat $mySeat isn't in any layout of this aircraft — it may be a different one.",
                )
                else -> null
            }
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SeatCanvas(map, mySeat, selected?.number, onSelect = { selected = it })
        }
        SeatInfo(map, selected, mine = selected != null && selected?.number == mySeat, mySeat = mySeat, onOtherAircraft = onOtherAircraft)
    }
}

@Composable
private fun SeatInfo(map: SeatMap, seat: SmSeat?, mine: Boolean, mySeat: String?, onOtherAircraft: () -> Unit) {
    val colors = LocalStatusColors.current
    // Sits on the bottom edge: the Scaffold's padding already clears the navigation bar.
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (seat == null) {
                Text(
                    if (mySeat != null) {
                        tr("Места $mySeat нет на этой схеме", "Seat $mySeat isn't on this map")
                    } else {
                        tr("Нажмите на кресло, чтобы узнать о нём", "Tap a seat to see its details")
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                val cabin = SeatMapLogic.cabinOfSeat(seat)
                Text(
                    listOf(
                        seat.number,
                        SeatMapLogic.cabinLabel(cabin),
                        SeatMapLogic.positionLabel(SeatMapLogic.position(map, seat)),
                        SeatMapLogic.sideLabel(seat),
                    ).joinToString(" · ") + if (mine) "  ·  " + tr("ваше место", "your seat") else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SeatMapLogic.featureLabels(seat).forEach { (text, good) ->
                    Text((if (good) "✓ " else "• ") + text, color = if (good) colors.good else colors.warn, style = MaterialTheme.typography.bodyMedium)
                }
                val spec = map.classSpecs[seat.classCode]
                val specText = listOfNotNull(
                    SeatMapLogic.inches(spec?.pitch)?.let { tr("шаг", "pitch") + " $it" },
                    SeatMapLogic.inches(spec?.width)?.let { tr("ширина", "width") + " $it" },
                ).joinToString(" · ")
                if (specText.isNotEmpty()) {
                    Text(specText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (cabin == Cabin.ECONOMY && seat.label.contains("+")) {
                    Text(seat.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Схема: seatmaps.com", "Seat map: seatmaps.com"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onOtherAircraft) { Text(tr("Другой самолёт", "Other aircraft")) }
            }
        }
    }
}

/**
 * Cabin extents and outline in the site's units (x from the centre line). The outline is the
 * site's own floor shape when the page has a sane one, else a generic tapered nose and tail.
 */
private class CabinGeometry(
    val outline: Path,
    val half: Float,
    val top: Float,
    val bottom: Float,
    val rows: Map<Int, List<SmSeat>>,
    val aisleX: Float?,
    val icons: List<SeatMapLogic.Icon>,
) {
    companion object {
        fun of(map: SeatMap): CabinGeometry {
            val items = map.seats.map { Rect(it.x, it.y, it.x + it.w, it.y + it.h) } +
                (map.blocks + map.stickers).map { Rect(it.x, it.y, it.x + it.w, it.y + it.h) }
            var left = items.minOf { it.left }
            var right = items.maxOf { it.right }
            var top = items.minOf { it.top }
            var bottom = items.maxOf { it.bottom }

            val site = map.outline
            val outline: Path
            if (site != null) {
                outline = Path().apply {
                    fillType = PathFillType.EvenOdd
                    site.rings.forEach { r ->
                        moveTo(r[0], r[1])
                        for (i in 2 until r.size - 1 step 2) lineTo(r[i], r[i + 1])
                        close()
                    }
                }
                site.rings.forEach { r ->
                    for (i in 0 until r.size - 1 step 2) {
                        left = min(left, r[i])
                        right = max(right, r[i])
                        top = min(top, r[i + 1])
                        bottom = max(bottom, r[i + 1])
                    }
                }
            } else {
                val w = max(abs(left), abs(right)) + 40f
                val t = top - 60f
                val bt = bottom + 60f
                val nose = w * 1.1f
                val tail = w * 1.5f
                outline = Path().apply {
                    moveTo(-w, t)
                    cubicTo(-w, t - nose * 0.55f, -w * 0.55f, t - nose, 0f, t - nose)
                    cubicTo(w * 0.55f, t - nose, w, t - nose * 0.55f, w, t)
                    lineTo(w, bt)
                    cubicTo(w, bt + tail * 0.5f, w * 0.4f, bt + tail * 0.9f, w * 0.25f, bt + tail)
                    lineTo(-w * 0.25f, bt + tail)
                    cubicTo(-w * 0.4f, bt + tail * 0.9f, -w, bt + tail * 0.5f, -w, bt)
                    close()
                }
                left = -w
                right = w
                top = t - nose
                bottom = bt + tail
            }

            val rows = map.seats.groupBy { it.row }
            val seatW = map.seats.map { it.w }.average().toFloat()
            val aisles = rows.values.mapNotNull { r -> widestGap(r)?.takeIf { it.second > seatW * 0.5f }?.first }.sorted()
            return CabinGeometry(
                outline = outline,
                half = max(abs(left), abs(right)) + 16f,
                top = top - 24f,
                bottom = bottom + 24f,
                rows = rows,
                aisleX = aisles.getOrNull(aisles.size / 2),
                icons = SeatMapLogic.icons(map),
            )
        }

        /** Centre and width of the widest gap between neighbouring seats of a row. */
        fun widestGap(row: List<SmSeat>): Pair<Float, Float>? =
            row.sortedBy { it.x }.zipWithNext()
                .map { (a, b) -> (a.x + a.w + b.x) / 2 to b.x - (a.x + a.w) }
                .maxByOrNull { it.second }
    }
}

/** Draws the cabin from the site's coordinates; x is relative to the fuselage centre line. */
@Composable
private fun SeatCanvas(map: SeatMap, mySeat: String?, selected: String?, onSelect: (SmSeat) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val exitColor = LocalStatusColors.current.bad
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val wcIcon = rememberVectorPainter(Icons.Default.Wc)
    val galleyIcon = rememberVectorPainter(Icons.Default.LocalCafe)
    val geo = remember(map.id) { CabinGeometry.of(map) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val local = LocalDensity.current
        val widthPx = with(local) { maxWidth.toPx() }
        val scale = widthPx / (geo.half * 2f)
        val heightDp = with(local) { ((geo.bottom - geo.top) * scale).toDp() }
        val viewport = with(local) { maxHeight.toPx() }
        val scroll = rememberScrollState()
        LaunchedEffect(map.id, mySeat) {
            val seat = mySeat?.let { map.seat(it) } ?: return@LaunchedEffect
            scroll.scrollTo(((seat.y - geo.top) * scale - viewport / 2).toInt().coerceAtLeast(0))
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(heightDp)
                    .pointerInput(map.id) {
                        detectTapGestures { o ->
                            val mx = o.x / scale - geo.half
                            val my = o.y / scale + geo.top
                            map.seats.firstOrNull { mx >= it.x && mx <= it.x + it.w && my >= it.y && my <= it.y + it.h }?.let(onSelect)
                        }
                    },
            ) {
                fun ox(x: Float) = (x + geo.half) * scale
                fun oy(y: Float) = (y - geo.top) * scale

                // Cabin floor in site units, mapped the same way as everything else.
                withTransform({
                    translate(ox(0f), oy(0f))
                    scale(scale, scale, pivot = Offset.Zero)
                }) {
                    drawPath(geo.outline, scheme.surfaceContainerHigh)
                    drawPath(geo.outline, scheme.outlineVariant, style = Stroke(width = 2.dp.toPx() / scale))
                }

                map.blocks.forEach { b ->
                    drawRoundRect(
                        color = scheme.surfaceVariant,
                        topLeft = Offset(ox(b.x), oy(b.y)),
                        size = Size(b.w * scale, b.h * scale),
                        cornerRadius = CornerRadius(12f * scale),
                    )
                }
                geo.icons.forEach { icon ->
                    val painter = when (icon.kind) {
                        "l" -> wcIcon
                        "g" -> galleyIcon
                        else -> null
                    } ?: return@forEach
                    val side = icon.size * scale
                    translate(ox(icon.x) - side / 2, oy(icon.y) - side / 2) {
                        with(painter) { draw(Size(side, side), colorFilter = ColorFilter.tint(scheme.onSurfaceVariant)) }
                    }
                }

                // Exit rows: red marks just outside the row, on both sides.
                geo.rows.values.filter { r -> r.any { it.has("exitRow") } }.forEach { r ->
                    val y = r.minOf { it.y } - 30f
                    val markW = 14f * scale
                    val markH = 70f * scale
                    drawRect(exitColor, Offset(ox(r.minOf { it.x } - 34f) - markW / 2, oy(y) - markH / 2), Size(markW, markH))
                    drawRect(exitColor, Offset(ox(r.maxOf { it.x + it.w } + 34f) - markW / 2, oy(y) - markH / 2), Size(markW, markH))
                }

                map.seats.forEach { seat ->
                    val mine = seat.number == mySeat
                    val cabin = SeatMapLogic.cabinOfSeat(seat)
                    val fill = when {
                        mine -> scheme.primary
                        seat.number == selected -> scheme.tertiary
                        cabin == Cabin.FIRST || cabin == Cabin.BUSINESS -> scheme.tertiaryContainer
                        cabin == Cabin.PREMIUM || seat.has("extraLegroom") -> scheme.secondaryContainer
                        else -> scheme.surfaceContainerLowest
                    }
                    val textColor = when {
                        mine -> scheme.onPrimary
                        seat.number == selected -> scheme.onTertiary
                        else -> scheme.onSurface
                    }
                    val tl = Offset(ox(seat.x), oy(seat.y))
                    val size = Size(seat.w * scale, seat.h * scale * 0.82f)
                    drawRoundRect(fill, tl, size, CornerRadius(size.width * 0.22f))
                    if (!mine) drawRoundRect(scheme.outlineVariant, tl, size, CornerRadius(size.width * 0.22f), style = Stroke(1f))
                    val fontPx = (seat.w * scale * 0.3f).coerceIn(8f.dp.toPx(), 13f.dp.toPx())
                    centeredText(
                        measurer, seat.number, Offset(tl.x + size.width / 2, tl.y + size.height / 2), textColor,
                        fontPx / (density * fontScale), bold = mine,
                    )
                    if (mine) {
                        drawRoundRect(scheme.primary, tl - Offset(4f, 4f), Size(size.width + 8f, size.height + 8f), CornerRadius(size.width * 0.3f), style = Stroke(3.dp.toPx()))
                    }
                }

                // Row numbers in the aisle; rows without one (e.g. a lone left block) use the cabin's aisle.
                val seatW = map.seats.first().w
                geo.rows.forEach { (row, seats) ->
                    val gap = CabinGeometry.widestGap(seats)
                    val cx = gap?.takeIf { it.second > seatW * 0.5f }?.first ?: geo.aisleX ?: return@forEach
                    val first = seats.minBy { it.y }
                    centeredText(measurer, row.toString(), Offset(ox(cx), oy(first.y + first.h * 0.41f)), scheme.onSurfaceVariant, 10f)
                }
            }
        }
    }
}

private fun DrawScope.centeredText(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    center: Offset,
    color: Color,
    sizeSp: Float,
    bold: Boolean = false,
) {
    val layout = measurer.measure(
        text,
        TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium),
    )
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}
