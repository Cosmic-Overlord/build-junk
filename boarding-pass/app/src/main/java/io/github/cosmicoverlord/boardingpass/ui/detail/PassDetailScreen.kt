package io.github.cosmicoverlord.boardingpass.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.CodeFormat
import io.github.cosmicoverlord.boardingpass.core.model.FlightStatus
import io.github.cosmicoverlord.boardingpass.core.model.PassText
import io.github.cosmicoverlord.boardingpass.core.model.toOdt
import io.github.cosmicoverlord.boardingpass.ui.AppViewModel
import io.github.cosmicoverlord.boardingpass.ui.barcode.BarcodeView
import io.github.cosmicoverlord.boardingpass.ui.components.Fact
import io.github.cosmicoverlord.boardingpass.ui.components.FactRow
import io.github.cosmicoverlord.boardingpass.ui.components.Fmt
import io.github.cosmicoverlord.boardingpass.ui.components.HeroHeader
import io.github.cosmicoverlord.boardingpass.ui.components.IconLine
import io.github.cosmicoverlord.boardingpass.ui.components.SectionCard
import io.github.cosmicoverlord.boardingpass.ui.components.StatusChip
import io.github.cosmicoverlord.boardingpass.ui.theme.LocalStatusColors
import java.time.LocalDate

@Composable
fun PassDetailScreen(
    vm: AppViewModel,
    passId: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onShowCode: () -> Unit,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
) {
    val passes by vm.passes.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pass = passes.firstOrNull { it.id == passId }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pass?.flightLabel?.ifBlank { null } ?: "Посадочный") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    if (pass != null) {
                        if (passId in refreshing) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { vm.refresh(passId) }) { Icon(Icons.Default.Refresh, contentDescription = "Обновить статус") }
                        }
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Изменить") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Удалить") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (pass == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Талон удалён") }
            return@Scaffold
        }
        val from = vm.airports[pass.from]
        val to = vm.airports[pass.to]
        val st = pass.status
        val dep = st?.depEstimated.toOdt() ?: st?.depScheduled.toOdt()
        val arr = st?.arrEstimated.toOdt() ?: st?.arrScheduled.toOdt()

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroHeader(
                pass = pass,
                airlineName = st?.airlineName ?: vm.airlines.name(pass.carrier),
                from = from,
                to = to,
                depTime = Fmt.time(dep),
                arrTime = Fmt.timeOn(arr, dep?.toLocalDate() ?: pass.date),
                dateText = Fmt.date(pass.date) + if (pass.legCount > 1) " · сегмент ${pass.legIndex + 1} из ${pass.legCount}" else "",
            )

            BarcodeCard(pass, onShowCode)

            StatusCard(
                pass = pass,
                status = st,
                trackingEnabled = settings.trackingEnabled,
                refreshing = passId in refreshing,
                onRefresh = { vm.refresh(passId) },
                onSettings = onSettings,
            )

            SectionCard("Пассажир") {
                Fact("Имя", PassText.passenger(pass.passengerName).ifBlank { null }, emphasize = true)
                FactRow(
                    "Место" to pass.seat,
                    "Класс" to listOfNotNull(pass.compartment.ifBlank { null }, PassText.cabinClass(pass.compartment))
                        .joinToString(" · ").ifBlank { null },
                    "Номер" to pass.sequence.ifBlank { null },
                    emphasize = true,
                )
                FactRow("Бронь (PNR)" to pass.pnr, "Тип" to pass.passengerType)
                if (pass.ticketNumber != null || pass.baggageAllowance != null) {
                    FactRow("Билет" to pass.ticketNumber, "Багаж" to pass.baggageAllowance)
                }
                if (pass.frequentFlyer != null || pass.marketingCarrier != null) {
                    FactRow(
                        "Бонусная карта" to pass.frequentFlyer,
                        "Продавец" to pass.marketingCarrier?.let { c -> vm.airlines.name(c)?.let { "$it ($c)" } ?: c },
                    )
                }
            }

            SectionCard("Аэропорты") {
                Fact("Вылет", from?.let { "${it.name} (${it.iata})" } ?: pass.from)
                Fact("Прилёт", to?.let { "${it.name} (${it.iata})" } ?: pass.to)
            }

            RawDataCard(pass)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить посадочный?") },
            text = { Text("Талон ${pass?.flightLabel.orEmpty()} будет удалён с устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(passId)
                    onBack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun BarcodeCard(pass: BoardingPass, onShowCode: () -> Unit) {
    SectionCard(null) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (pass.format == CodeFormat.PDF_417 || pass.format == CodeFormat.CODE_128) 120.dp else 220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .clickable(onClick = onShowCode)
                .padding(12.dp),
        ) {
            BarcodeView(pass.barcode, pass.format, Modifier.fillMaxSize())
        }
        Button(onClick = onShowCode, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Fullscreen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Показать код на весь экран")
        }
    }
}

@Composable
private fun StatusCard(
    pass: BoardingPass,
    status: FlightStatus?,
    trackingEnabled: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    SectionCard("Статус рейса") {
        if (!trackingEnabled && status == null) {
            Text(
                "Чтобы видеть актуальный статус, терминал и выход на посадку, выберите источник данных и укажите API-ключ.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Настроить")
            }
            return@SectionCard
        }
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status.kind)
                Spacer(Modifier.size(8.dp))
                status.statusText?.takeIf { !it.equals(status.kind.name, ignoreCase = true) }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FactRow("Терминал" to status.depTerminal, "Выход" to status.depGate, "Регистрация" to status.depCheckIn, emphasize = true)

            val date = pass.date
            val depSched = status.depScheduled.toOdt()
            val depEst = status.depEstimated.toOdt()
            val depAct = status.depActual.toOdt()
            val arrSched = status.arrScheduled.toOdt()
            val arrEst = status.arrEstimated.toOdt()
            val arrAct = status.arrActual.toOdt()
            val warn = LocalStatusColors.current.warn
            TimesRow("Вылет", Fmt.timeOn(depSched, date), Fmt.timeOn(depEst, date), Fmt.timeOn(depAct, date), Fmt.delay(depSched, depAct ?: depEst), warn)
            TimesRow("Прилёт", Fmt.timeOn(arrSched, date), Fmt.timeOn(arrEst, date), Fmt.timeOn(arrAct, date), Fmt.delay(arrSched, arrAct ?: arrEst), warn)
            if (status.arrTerminal != null || status.arrGate != null || status.baggageBelt != null) {
                FactRow("Терминал прилёта" to status.arrTerminal, "Выход" to status.arrGate, "Лента багажа" to status.baggageBelt)
            }
            if (status.aircraftModel != null || status.aircraftReg != null) {
                FactRow("Самолёт" to status.aircraftModel, "Борт" to status.aircraftReg)
            }
        }
        pass.statusError?.let {
            IconLine(Icons.Default.ErrorOutline, it, color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconLine(
                Icons.Default.Schedule,
                "Обновлено: " + Fmt.ago(status?.fetchedAt ?: pass.statusCheckedAt),
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRefresh, enabled = !refreshing) { Text(if (refreshing) "Обновляю…" else "Обновить") }
        }
        if (status == null && pass.date?.isAfter(LocalDate.now().plusDays(2)) == true) {
            Text(
                "Терминал и выход обычно известны за несколько часов до вылета.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimesRow(label: String, scheduled: String?, estimated: String?, actual: String?, delay: String?, warn: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (delay != null) {
                Spacer(Modifier.size(8.dp))
                Text(delay, style = MaterialTheme.typography.labelMedium, color = warn, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Fact("План", scheduled, Modifier.weight(1f))
            Fact("Ожидается", estimated, Modifier.weight(1f))
            Fact("Факт", actual, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RawDataCard(pass: BoardingPass) {
    var open by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    SectionCard(null) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Данные штрихкода · ${formatName(pass.format)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pass.barcode, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { clipboard.setText(AnnotatedString(pass.barcode)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Скопировать")
                }
            }
        }
    }
}

private fun formatName(f: CodeFormat) = when (f) {
    CodeFormat.QR_CODE -> "QR"
    CodeFormat.AZTEC -> "Aztec"
    CodeFormat.PDF_417 -> "PDF417"
    CodeFormat.DATA_MATRIX -> "Data Matrix"
    CodeFormat.CODE_128 -> "Code 128"
    CodeFormat.OTHER -> "другой"
}
