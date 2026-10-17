package io.github.cosmicoverlord.boardingpass.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cosmicoverlord.boardingpass.BuildConfig
import io.github.cosmicoverlord.boardingpass.core.status.ProviderType
import io.github.cosmicoverlord.boardingpass.tracking.Notifications
import io.github.cosmicoverlord.boardingpass.ui.AppViewModel
import io.github.cosmicoverlord.boardingpass.ui.components.SectionCard

private val REFRESH_OPTIONS = listOf(0 to "Выкл", 15 to "15 м", 30 to "30 м", 60 to "1 ч", 120 to "2 ч")

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showKey by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context) && !settings.askedNotificationPermission) {
            vm.updateSettings { it.copy(askedNotificationPermission = true) }
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Источник статуса рейсов") {
                Text(
                    "Посадочный талон не содержит терминал, выход и тип самолёта — их приложение берёт " +
                        "из онлайн-сервиса. Выберите сервис и вставьте свой API-ключ (у всех есть бесплатный тариф).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderType.entries.forEach { type ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = settings.provider == type,
                                role = Role.RadioButton,
                                onClick = {
                                    vm.updateSettings { it.copy(provider = type) }
                                    if (type != ProviderType.NONE) askNotificationsOnce()
                                },
                            ),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = settings.provider == type, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(type.title, style = MaterialTheme.typography.titleSmall)
                            Text(type.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val type = settings.provider
                if (type != ProviderType.NONE) {
                    OutlinedTextField(
                        value = settings.key(type),
                        onValueChange = { v -> vm.updateSettings { it.copy(keys = it.keys + (type to v.trim())) } },
                        label = { Text("API-ключ ${type.title}") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Показать ключ")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    type.signupUrl?.let { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) {
                            Text("Получить ключ")
                            Spacer(Modifier.padding(4.dp))
                            Icon(Icons.Default.Link, contentDescription = null)
                        }
                    }
                }
            }

            SectionCard("Отслеживание") {
                Text(
                    "Фоновое обновление статуса — с дня до вылета и до прилёта. При смене выхода, терминала, " +
                        "времени или статуса придёт уведомление. Чаще — точнее, но быстрее тратится лимит запросов API.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    REFRESH_OPTIONS.forEachIndexed { index, (minutes, label) ->
                        SegmentedButton(
                            selected = settings.refreshMinutes == minutes,
                            onClick = { vm.updateSettings { it.copy(refreshMinutes = minutes) } },
                            shape = SegmentedButtonDefaults.itemShape(index, REFRESH_OPTIONS.size),
                        ) { Text(label) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Уведомления об изменениях", style = MaterialTheme.typography.titleSmall)
                        if (!Notifications.canNotify(context)) {
                            Text(
                                "Разрешение на уведомления не выдано",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Switch(
                        checked = settings.notifications,
                        onCheckedChange = { on ->
                            vm.updateSettings { it.copy(notifications = on) }
                            if (on && Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context)) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }

            SectionCard("О приложении") {
                Text("Посадочные ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Данные посадочного читаются из штрихкода по стандарту IATA BCBP (Resolution 792). " +
                        "Справочник аэропортов — OurAirports (public domain) и mwgg/Airports, " +
                        "авиакомпаний — OpenFlights (ODbL). Все талоны хранятся только на этом устройстве.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
