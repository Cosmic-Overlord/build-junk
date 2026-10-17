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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cosmicoverlord.boardingpass.BuildConfig
import io.github.cosmicoverlord.boardingpass.core.I18n
import io.github.cosmicoverlord.boardingpass.core.status.ProviderType
import io.github.cosmicoverlord.boardingpass.core.tr
import io.github.cosmicoverlord.boardingpass.data.AppLanguage
import io.github.cosmicoverlord.boardingpass.data.ThemeMode
import io.github.cosmicoverlord.boardingpass.tracking.LiveUpdates
import io.github.cosmicoverlord.boardingpass.tracking.Notifications
import io.github.cosmicoverlord.boardingpass.ui.AppViewModel
import io.github.cosmicoverlord.boardingpass.ui.components.SectionCard
import io.github.cosmicoverlord.boardingpass.ui.components.findActivity

private val EDGE_OPTIONS = listOf(0, 2, 3, 4, 5)
private val RUSH_OPTIONS = listOf(1, 2, 5)
private val LIVE_LEAD_OPTIONS = listOf(1, 2, 3, 6)

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showKey by remember { mutableStateOf(false) }
    var canPromote by remember { mutableStateOf(LiveUpdates.canPromote(context)) }
    // The user may come back from the system settings having allowed Live Updates.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canPromote = LiveUpdates.canPromote(context) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val refreshOptions = listOf(
        0 to tr("Выкл", "Off"), 15 to tr("15 м", "15 m"), 30 to tr("30 м", "30 m"),
        60 to tr("1 ч", "1 h"), 120 to tr("2 ч", "2 h"),
    )

    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context) && !settings.askedNotificationPermission) {
            vm.updateSettings { it.copy(askedNotificationPermission = true) }
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Настройки", "Settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Назад", "Back")) }
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
            SectionCard(tr("Источник статуса рейсов", "Flight status source")) {
                Text(
                    tr(
                        "Посадочный талон не содержит терминал, выход и тип самолёта — их приложение берёт " +
                            "из онлайн-сервиса. Выберите сервис и вставьте свой API-ключ (у всех есть бесплатный тариф).",
                        "A boarding pass has no terminal, gate or aircraft type — the app gets them from an online " +
                            "service. Pick one and paste your API key (all have a free plan).",
                    ),
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
                        label = { Text(tr("API-ключ", "API key") + " ${type.title}") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = tr("Показать ключ", "Show key"),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    type.signupUrl?.let { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) {
                            Text(tr("Получить ключ", "Get a key"))
                            Spacer(Modifier.padding(4.dp))
                            Icon(Icons.Default.Link, contentDescription = null)
                        }
                    }
                }
            }

            SectionCard(tr("Отслеживание", "Tracking")) {
                Text(
                    tr(
                        "Фоновое обновление статуса — с дня до вылета и до прилёта. При смене выхода, терминала, " +
                            "времени или статуса придёт уведомление. Чаще — точнее, но быстрее тратится лимит запросов API.",
                        "Background status refresh from the day before departure until arrival, with a notification " +
                            "when the gate, terminal, time or status changes. More often is fresher but uses the API quota faster.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    refreshOptions.forEachIndexed { index, (minutes, label) ->
                        SegmentedButton(
                            selected = settings.refreshMinutes == minutes,
                            onClick = { vm.updateSettings { it.copy(refreshMinutes = minutes) } },
                            shape = SegmentedButtonDefaults.itemShape(index, refreshOptions.size),
                        ) { Text(label) }
                    }
                }
                SwitchRow(
                    title = tr("Уведомления об изменениях", "Change notifications"),
                    subtitle = if (!Notifications.canNotify(context)) tr("Разрешение на уведомления не выдано", "Notification permission not granted") else null,
                    checked = settings.notifications,
                    error = !Notifications.canNotify(context),
                ) { on ->
                    vm.updateSettings { it.copy(notifications = on) }
                    if (on && Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(context)) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            SectionCard(tr("Перед вылетом", "Before departure")) {
                SwitchRow(
                    title = tr("Частое обновление за час до вылета", "Frequent refresh in the last hour"),
                    subtitle = tr(
                        "С T−60 мин до вылета статус и выход проверяются каждые ${settings.rushIntervalMinutes} мин: " +
                            "гейт могут сменить в последний момент. В шторке висит уведомление с выходом. " +
                            "Это ≈${60 / settings.rushIntervalMinutes.coerceAtLeast(1)} запросов на рейс — у Aviationstack (100/мес) это заметная часть лимита.",
                        "From T−60 min until departure the status and gate are checked every ${settings.rushIntervalMinutes} min, " +
                            "since gates change at the last moment. An ongoing notification shows the gate. " +
                            "That's ≈${60 / settings.rushIntervalMinutes.coerceAtLeast(1)} requests per flight — a big share of Aviationstack's 100/month.",
                    ),
                    checked = settings.rushEnabled,
                ) { on ->
                    vm.updateSettings { it.copy(rushEnabled = on) }
                    if (on) askNotificationsOnce()
                }
                if (settings.rushEnabled) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        RUSH_OPTIONS.forEachIndexed { index, minutes ->
                            SegmentedButton(
                                selected = settings.rushIntervalMinutes == minutes,
                                onClick = { vm.updateSettings { it.copy(rushIntervalMinutes = minutes) } },
                                shape = SegmentedButtonDefaults.itemShape(index, RUSH_OPTIONS.size),
                            ) { Text(tr("каждые $minutes мин", "every $minutes min")) }
                        }
                    }
                }
                if (!settings.trackingEnabled) {
                    Text(
                        tr("Работает, когда выбран источник статуса и указан ключ.", "Works once a status source and key are set."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionCard(tr("Эфир рейса", "Live flight")) {
                SwitchRow(
                    title = tr("Карточка рейса в строке состояния", "Flight card in the status bar"),
                    subtitle = tr(
                        "Как «Эфир активности» на iPhone: на экране блокировки и в шторке — рейс, время вылета и " +
                            "прилёта по местному, выход и полоса прогресса с самолётом; у часов — выход до вылета и " +
                            "оставшееся время в полёте. Отсчёт идёт сам, без интернета.",
                        "Like Live Activities on iPhone: the flight, local departure and arrival times, the gate and " +
                            "a progress bar with the plane on the lock screen; next to the clock, the gate before " +
                            "departure and the time left in the air. Countdowns run offline.",
                    ),
                    checked = settings.liveUpdates,
                ) { on ->
                    vm.updateSettings { it.copy(liveUpdates = on) }
                    if (on) askNotificationsOnce()
                }
                if (settings.liveUpdates) {
                    Text(tr("Показывать за", "Show from"), style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        LIVE_LEAD_OPTIONS.forEachIndexed { index, hours ->
                            SegmentedButton(
                                selected = settings.liveLeadHours == hours,
                                onClick = { vm.updateSettings { it.copy(liveLeadHours = hours) } },
                                shape = SegmentedButtonDefaults.itemShape(index, LIVE_LEAD_OPTIONS.size),
                            ) { Text(tr("$hours ч", "$hours h")) }
                        }
                    }
                    if (!canPromote) {
                        Text(
                            tr(
                                "Android не показывает карточку у часов: оперативные уведомления для приложения выключены.",
                                "Android won't show the card next to the clock: Live Updates are off for this app.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { LiveUpdates.openPromotionSettings(context) }) {
                            Text(tr("Включить в настройках Android", "Turn on in Android settings"))
                        }
                    }
                    if (!settings.trackingEnabled) {
                        Text(
                            tr(
                                "Время вылета и прилёта берётся из статуса рейса — нужен источник статуса и ключ.",
                                "Departure and arrival times come from the flight status — set a status source and key.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            SectionCard(tr("Код на весь экран", "Full-screen code")) {
                Text(
                    tr(
                        "Отступ кода от изогнутых боковых краёв экрана: на изгибе модули кода искажаются и сканер " +
                            "у выхода может его не прочитать. Для Motorola Edge 50 Ultra — 4 мм.",
                        "Margin from the curved screen edges: on the curve the code's modules are distorted and the gate " +
                            "scanner may fail to read it. 4 mm suits the Motorola Edge 50 Ultra.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    EDGE_OPTIONS.forEachIndexed { index, mm ->
                        SegmentedButton(
                            selected = settings.edgeMarginMm == mm,
                            onClick = { vm.updateSettings { it.copy(edgeMarginMm = mm) } },
                            shape = SegmentedButtonDefaults.itemShape(index, EDGE_OPTIONS.size),
                        ) { Text(if (mm == 0) tr("Нет", "None") else tr("$mm мм", "$mm mm")) }
                    }
                }
            }

            SectionCard(tr("Интерфейс", "Interface")) {
                Text(tr("Язык", "Language"), style = MaterialTheme.typography.titleSmall)
                val languages = listOf(AppLanguage.SYSTEM to tr("Системный", "System"), AppLanguage.RU to "Русский", AppLanguage.EN to "English")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    languages.forEachIndexed { index, (lang, label) ->
                        SegmentedButton(
                            selected = settings.language == lang,
                            onClick = {
                                if (settings.language != lang) {
                                    vm.updateSettings { it.copy(language = lang) }
                                    I18n.lang = lang.resolve()
                                    context.findActivity()?.recreate()
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, languages.size),
                        ) { Text(label) }
                    }
                }
                Text(tr("Тема", "Theme"), style = MaterialTheme.typography.titleSmall)
                val themes = listOf(
                    ThemeMode.SYSTEM to tr("Системная", "System"),
                    ThemeMode.LIGHT to tr("Светлая", "Light"),
                    ThemeMode.DARK to tr("Тёмная", "Dark"),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    themes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { vm.updateSettings { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, themes.size),
                        ) { Text(label) }
                    }
                }
                SwitchRow(
                    title = tr("Анимации переходов", "Screen transitions"),
                    subtitle = tr("Плавная смена экранов. Выключите, чтобы экраны открывались мгновенно.", "Fade between screens. Turn off to switch instantly."),
                    checked = settings.animations,
                ) { on -> vm.updateSettings { it.copy(animations = on) } }
            }

            SectionCard(tr("О приложении", "About")) {
                Text(tr("Посадочные", "Boarding Passes") + " ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(
                        "Данные посадочного читаются из штрихкода по стандарту IATA BCBP (Resolution 792). " +
                            "Справочник аэропортов — OurAirports (public domain) и mwgg/Airports, " +
                            "авиакомпаний — OpenFlights (ODbL). Схемы салонов — seatmaps.com, загружаются по кнопке " +
                            "для личного использования. Все данные хранятся только на этом устройстве.",
                        "Boarding pass data is read from the barcode (IATA BCBP, Resolution 792). " +
                            "Airports: OurAirports (public domain) and mwgg/Airports; airlines: OpenFlights (ODbL). " +
                            "Seat maps: seatmaps.com, loaded on request for personal use. All data stays on this device.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, error: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
