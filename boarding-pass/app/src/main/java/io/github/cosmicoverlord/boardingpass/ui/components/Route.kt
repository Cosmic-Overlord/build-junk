package io.github.cosmicoverlord.boardingpass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cosmicoverlord.boardingpass.core.data.Airport
import io.github.cosmicoverlord.boardingpass.core.data.Geo
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.ui.theme.HeroGradient

@Composable
private fun AirportBlock(
    code: String,
    airport: Airport?,
    time: String?,
    align: Alignment.Horizontal,
    textColor: Color,
    big: Boolean,
    modifier: Modifier = Modifier,
) {
    val textAlign = if (align == Alignment.End) TextAlign.End else TextAlign.Start
    Column(modifier, horizontalAlignment = align) {
        Text(
            code.ifBlank { "???" },
            style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
        Text(
            airport?.displayCity ?: "Неизвестный аэропорт",
            style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
        if (airport != null) {
            Text(
                "${Geo.flag(airport.country)} ${Geo.countryName(airport.country)}".trim(),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
            )
        }
        if (time != null) {
            Text(
                time,
                style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
            )
        }
    }
}

@Composable
fun RouteRow(
    pass: BoardingPass,
    from: Airport?,
    to: Airport?,
    textColor: Color,
    big: Boolean,
    depTime: String? = null,
    arrTime: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AirportBlock(pass.from, from, depTime, Alignment.Start, textColor, big, Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.size(width = if (big) 22.dp else 14.dp, height = 1.dp), color = textColor.copy(alpha = 0.5f))
                Icon(
                    Icons.Default.Flight,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.padding(horizontal = 4.dp).size(if (big) 30.dp else 22.dp).rotate(90f),
                )
                HorizontalDivider(Modifier.size(width = if (big) 22.dp else 14.dp, height = 1.dp), color = textColor.copy(alpha = 0.5f))
            }
            if (big && from != null && to != null) {
                Text(
                    "${Geo.distanceKm(from, to)} км",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.8f),
                )
            }
        }
        AirportBlock(pass.to, to, arrTime, Alignment.End, textColor, big, Modifier.weight(1f))
    }
}

/** Gradient header of the detail screen. */
@Composable
fun HeroHeader(
    pass: BoardingPass,
    airlineName: String?,
    from: Airport?,
    to: Airport?,
    depTime: String?,
    arrTime: String?,
    dateText: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(HeroGradient))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    airlineName ?: pass.carrier,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pass.flightLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(12.dp))
            RouteRow(pass, from, to, Color.White, big = true, depTime = depTime, arrTime = arrTime)
            Spacer(Modifier.height(12.dp))
            Text(dateText, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
        }
    }
}
