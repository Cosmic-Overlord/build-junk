package io.github.cosmicoverlord.boardingpass.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.cosmicoverlord.boardingpass.ui.AppViewModel
import io.github.cosmicoverlord.boardingpass.ui.components.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun EditPassScreen(vm: AppViewModel, passId: String, onClose: () -> Unit, onSaved: () -> Unit) {
    val pass = remember(passId) { vm.pass(passId) }
    if (pass == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var carrier by rememberSaveable { mutableStateOf(pass.carrier) }
    var number by rememberSaveable { mutableStateOf(pass.flightNumber) }
    var from by rememberSaveable { mutableStateOf(pass.from) }
    var to by rememberSaveable { mutableStateOf(pass.to) }
    var date by rememberSaveable { mutableStateOf(pass.flightDate) }
    var name by rememberSaveable { mutableStateOf(pass.passengerName) }
    var pnr by rememberSaveable { mutableStateOf(pass.pnr) }
    var seat by rememberSaveable { mutableStateOf(pass.seat) }
    var cabin by rememberSaveable { mutableStateOf(pass.compartment) }
    var sequence by rememberSaveable { mutableStateOf(pass.sequence) }
    var pickDate by remember { mutableStateOf(false) }

    val code3 = { s: String -> s.uppercase().filter { it in 'A'..'Z' }.take(3) }

    fun save() {
        vm.save(
            pass.copy(
                carrier = carrier.trim().uppercase(),
                flightNumber = number.trim().uppercase().trimStart('0').ifEmpty { number.trim() },
                from = from,
                to = to,
                flightDate = date,
                passengerName = name.trim().uppercase(),
                pnr = pnr.trim().uppercase(),
                seat = seat.trim().uppercase(),
                compartment = cabin.trim().uppercase().take(1),
                sequence = sequence.trim(),
            ),
        )
        onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pass.parsed) "Изменить талон" else "Данные рейса") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Закрыть") } },
                actions = { TextButton(onClick = ::save) { Text("Сохранить") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!pass.parsed) {
                Text(
                    "Код сохранён как есть и будет показываться на весь экран. Заполните рейс и дату — " +
                        "тогда приложение сможет следить за статусом, терминалом и выходом.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Авиакомпания", carrier, { carrier = it.uppercase().take(3) }, Modifier.weight(1f), hint = "SU")
                Field("Номер рейса", number, { number = it.uppercase().take(5) }, Modifier.weight(1f), hint = "1234")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Откуда (IATA)", from, { from = code3(it) }, Modifier.weight(1f), hint = "SVO")
                Field("Куда (IATA)", to, { to = code3(it) }, Modifier.weight(1f), hint = "LED")
            }
            listOfNotNull(vm.airports[from]?.let { "$from — ${it.name}" }, vm.airports[to]?.let { "$to — ${it.name}" })
                .forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DateRange, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(date?.let { runCatching { Fmt.date(LocalDate.parse(it)) }.getOrNull() } ?: "Выбрать дату вылета")
            }
            Field("Пассажир (ФАМИЛИЯ/ИМЯ)", name, { name = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Бронь (PNR)", pnr, { pnr = it.uppercase().take(7) }, Modifier.weight(1f))
                Field("Место", seat, { seat = it.uppercase().take(4) }, Modifier.weight(1f), hint = "12A")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Класс (буква)", cabin, { cabin = it.uppercase().take(1) }, Modifier.weight(1f), hint = "Y")
                Field("Порядковый №", sequence, { sequence = it.take(5) }, Modifier.weight(1f), number = true)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        }
    }

    if (pickDate) {
        val initial = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Отмена") } },
        ) {
            DatePicker(state)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    number: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (hint != null) {
            { Text(hint) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
        ),
        modifier = modifier,
    )
}
