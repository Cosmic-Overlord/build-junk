package io.github.cosmicoverlord.boardingpass.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cosmicoverlord.boardingpass.core.I18n
import io.github.cosmicoverlord.boardingpass.core.model.StatusKind
import io.github.cosmicoverlord.boardingpass.core.tr
import io.github.cosmicoverlord.boardingpass.ui.theme.LocalStatusColors
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun statusColor(kind: StatusKind): Color {
    val c = LocalStatusColors.current
    return when (kind) {
        StatusKind.SCHEDULED, StatusKind.CHECK_IN, StatusKind.LANDED, StatusKind.ARRIVED -> c.good
        StatusKind.BOARDING, StatusKind.DEPARTED, StatusKind.EN_ROUTE -> c.info
        StatusKind.DELAYED, StatusKind.GATE_CLOSED, StatusKind.DIVERTED -> c.warn
        StatusKind.CANCELLED -> c.bad
        StatusKind.UNKNOWN -> c.neutral
    }
}

@Composable
fun StatusChip(kind: StatusKind, modifier: Modifier = Modifier) {
    val color = statusColor(kind)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Text(
            kind.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

/** Small label-over-value block used in grids of boarding pass facts. */
@Composable
fun Fact(label: String, value: String?, modifier: Modifier = Modifier, emphasize: Boolean = false) {
    Column(modifier) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value?.ifBlank { null } ?: "—",
            style = if (emphasize) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun FactRow(vararg facts: Pair<String, String?>, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        facts.forEach { (label, value) -> Fact(label, value, Modifier.weight(1f), emphasize) }
    }
}

@Composable
fun IconLine(icon: ImageVector, text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

object Fmt {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private fun pattern(p: String) = DateTimeFormatter.ofPattern(p, I18n.locale)

    fun date(d: LocalDate?, today: LocalDate = LocalDate.now()): String {
        d ?: return tr("Дата неизвестна", "Date unknown")
        val base = pattern(if (d.year == today.year) "EEE, d MMMM" else "EEE, d MMMM yyyy").format(d)
        val rel = when (d) {
            today -> tr("сегодня", "today")
            today.plusDays(1) -> tr("завтра", "tomorrow")
            today.minusDays(1) -> tr("вчера", "yesterday")
            else -> null
        }
        return if (rel != null) "$base · $rel" else base
    }

    fun time(t: OffsetDateTime?): String? = t?.format(hm)

    /** Time with the date appended when it differs from the flight date. */
    fun timeOn(t: OffsetDateTime?, flightDate: LocalDate?): String? {
        t ?: return null
        val s = t.format(hm)
        return if (flightDate != null && t.toLocalDate() != flightDate) "$s (${t.format(pattern("d MMM"))})" else s
    }

    fun delay(scheduled: OffsetDateTime?, actual: OffsetDateTime?): String? {
        if (scheduled == null || actual == null) return null
        val m = Duration.between(scheduled, actual).toMinutes()
        return when {
            m >= 5 -> "+" + duration(m)
            m <= -5 -> "−" + duration(-m)
            else -> null
        }
    }

    fun duration(minutes: Long): String {
        val min = tr("мин", "min")
        return if (minutes >= 60) "${minutes / 60} ${tr("ч", "h")} ${minutes % 60} $min" else "$minutes $min"
    }

    fun ago(millis: Long?, now: Long = System.currentTimeMillis()): String {
        millis ?: return tr("никогда", "never")
        val m = (now - millis) / 60_000
        return when {
            m < 1 -> tr("только что", "just now")
            m < 60 -> tr("$m мин назад", "$m min ago")
            m < 24 * 60 -> tr("${m / 60} ч назад", "${m / 60} h ago")
            else -> tr("${m / (24 * 60)} дн назад", "${m / (24 * 60)} d ago")
        }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
