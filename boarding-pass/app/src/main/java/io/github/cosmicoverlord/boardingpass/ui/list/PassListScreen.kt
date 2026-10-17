package io.github.cosmicoverlord.boardingpass.ui.list

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.PassText
import io.github.cosmicoverlord.boardingpass.core.model.Tracking
import io.github.cosmicoverlord.boardingpass.core.model.toOdt
import io.github.cosmicoverlord.boardingpass.ui.AppViewModel
import io.github.cosmicoverlord.boardingpass.ui.components.Fmt
import io.github.cosmicoverlord.boardingpass.ui.components.RouteRow
import io.github.cosmicoverlord.boardingpass.ui.components.StatusChip
import io.github.cosmicoverlord.boardingpass.ui.theme.LocalStatusColors
import java.time.LocalDate
import io.github.cosmicoverlord.boardingpass.core.tr

/** Mime types offered by the "file" picker: PDFs, pictures and Apple Wallet passes. */
val IMPORT_MIME_TYPES = arrayOf(
    "application/pdf",
    "image/*",
    "application/vnd.apple.pkpass",
    "application/vnd-com.apple.pkpass",
    "application/zip",
    "application/octet-stream",
)

@Composable
fun PassListScreen(
    vm: AppViewModel,
    snackbar: SnackbarHostState,
    onOpen: (String) -> Unit,
    onShowCode: (String) -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
) {
    val passes by vm.passes.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::importUri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importUri)
    }

    LaunchedEffect(Unit) { vm.refreshStale() }

    val today = remember { LocalDate.now() }
    val sorted = remember(passes) {
        passes.sortedWith(
            compareBy<BoardingPass> { it.departureTime()?.toInstant() ?: it.date?.atStartOfDay()?.toInstant(java.time.ZoneOffset.UTC) }
                .thenBy { it.legIndex },
        )
    }
    val upcoming = sorted.filter { Tracking.isUpcoming(it, today) }
    val past = sorted.filterNot { Tracking.isUpcoming(it, today) }.reversed()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Посадочные талоны", "Boarding passes")) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = tr("Настройки", "Settings")) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(tr("Добавить", "Add")) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = AppViewModel.ALL in refreshing,
            onRefresh = vm::refreshAll,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (passes.isEmpty()) {
                EmptyState(onScan = onScan, onFile = { pickFile.launch(IMPORT_MIME_TYPES) })
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (upcoming.isNotEmpty()) {
                        item { SectionTitle(tr("Предстоящие", "Upcoming")) }
                        items(upcoming, key = { it.id }) { p ->
                            PassCard(vm, p, onClick = { onOpen(p.id) }, onShowCode = { onShowCode(p.id) })
                        }
                    }
                    if (past.isNotEmpty()) {
                        item { SectionTitle(tr("Прошедшие", "Past")) }
                        items(past, key = { it.id }) { p ->
                            PassCard(vm, p, onClick = { onOpen(p.id) }, onShowCode = { onShowCode(p.id) }, dimmed = true)
                        }
                    }
                    if (!settings.trackingEnabled) {
                        item {
                            Text(
                                tr("Статус рейса, терминал и выход появятся после выбора источника данных в настройках.", "Flight status, terminal and gate appear once you choose a data source in Settings."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(8.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showAdd = false }, sheetState = sheetState) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    tr("Добавить посадочный", "Add a boarding pass"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                AddOption(Icons.Default.QrCodeScanner, tr("Сканировать камерой", "Scan with the camera"), tr("Штрихкод на бумаге или экране другого телефона", "A barcode on paper or on another phone")) {
                    showAdd = false
                    onScan()
                }
                AddOption(Icons.Default.PhotoLibrary, tr("Из фото или скриншота", "From a photo or screenshot"), tr("Картинка из галереи", "A picture from the gallery")) {
                    showAdd = false
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                AddOption(Icons.Default.AttachFile, tr("Из файла", "From a file"), tr("PDF, изображение или Apple Wallet (.pkpass)", "PDF, image or Apple Wallet (.pkpass)")) {
                    showAdd = false
                    pickFile.launch(IMPORT_MIME_TYPES)
                }
            }
        }
    }
}

@Composable
private fun AddOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun PassCard(
    vm: AppViewModel,
    pass: BoardingPass,
    onClick: () -> Unit,
    onShowCode: () -> Unit,
    dimmed: Boolean = false,
) {
    val from = vm.airports[pass.from]
    val to = vm.airports[pass.to]
    val st = pass.status
    val airline = st?.airlineName ?: vm.airlines.name(pass.carrier)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (dimmed) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dimmed) 0.dp else 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pass.flightLabel.ifBlank { tr("Рейс не указан", "No flight number") }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (airline != null) {
                        Text(
                            airline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (st != null) StatusChip(st.kind)
                FilledTonalIconButton(onClick = onShowCode, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Default.QrCode, contentDescription = tr("Показать код", "Show code"))
                }
            }
            RouteRow(
                pass, from, to, MaterialTheme.colorScheme.onSurface, big = false,
                depTime = Fmt.time(st?.depEstimated.toOdt() ?: st?.depScheduled.toOdt()),
                arrTime = Fmt.time(st?.arrEstimated.toOdt() ?: st?.arrScheduled.toOdt()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fmt.date(pass.date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Mini(tr("Место", "Seat"), pass.seat)
                Mini(tr("Терм.", "Term."), st?.depTerminal)
                Mini(tr("Выход", "Gate"), st?.depGate)
            }
            if (pass.passengerName.isNotBlank()) {
                Text(
                    PassText.passenger(pass.passengerName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            pass.statusError?.let { error ->
                Text(
                    "⚠ " + tr("Статус не обновился", "Status not refreshed") + ": " + error,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalStatusColors.current.warn,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Mini(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.ifBlank { null } ?: "—", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyState(onScan: () -> Unit, onFile: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.ConfirmationNumber,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(tr("Пока нет посадочных", "No boarding passes yet"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                tr("Отсканируйте штрихкод посадочного талона камерой или загрузите PDF / скриншот. ", "Scan the boarding pass barcode with the camera or load a PDF / screenshot. ") +
                    tr("Можно также «Поделиться» файлом из почты или приложения авиакомпании.", "You can also \"Share\" a file from email or the airline app."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onScan) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(tr("Сканировать", "Scan"))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onFile) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(tr("PDF или картинка", "PDF or picture"))
            }
        }
    }
}
