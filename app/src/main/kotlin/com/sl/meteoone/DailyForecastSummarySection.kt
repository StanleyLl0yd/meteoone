package com.sl.meteoone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.time.format.DateTimeFormatter

@Composable
internal fun DailyForecastSummarySection(
    items: List<FusedHourlyForecast>,
    timeZoneId: String,
) {
    val summaries = remember(items, timeZoneId) {
        buildDailyForecastSummaries(items, timeZoneId)
    }
    if (summaries.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.daily_forecast_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            modifier = Modifier.padding(top = 2.dp),
            text = stringResource(R.string.daily_forecast_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = summaries,
                key = { summary -> summary.date.toEpochDay() },
            ) { summary ->
                DailyForecastSummaryCard(summary)
            }
        }
    }
}

@Composable
private fun DailyForecastSummaryCard(summary: DailyForecastSummary) {
    Card(
        modifier = Modifier.width(176.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = DAILY_DATE_FORMATTER.format(summary.date),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 10.dp),
                text = dailyConditionLabel(summary.representativeCondition),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = stringResource(R.string.daily_condition_midday_hint),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = summary.maximumTemperatureC?.let { value ->
                    stringResource(R.string.daily_high_temperature, value)
                } ?: stringResource(R.string.daily_high_unavailable),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = summary.minimumTemperatureC?.let { value ->
                    stringResource(R.string.daily_low_temperature, value)
                } ?: stringResource(R.string.daily_low_unavailable),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun dailyConditionLabel(condition: WeatherCondition): String = when (condition) {
    WeatherCondition.UNKNOWN -> stringResource(R.string.condition_unknown)
    WeatherCondition.CLEAR -> stringResource(R.string.condition_clear)
    WeatherCondition.PARTLY_CLOUDY -> stringResource(R.string.condition_partly_cloudy)
    WeatherCondition.CLOUDY -> stringResource(R.string.condition_cloudy)
    WeatherCondition.FOG -> stringResource(R.string.condition_fog)
    WeatherCondition.RAIN -> stringResource(R.string.condition_rain)
    WeatherCondition.HEAVY_RAIN -> stringResource(R.string.condition_heavy_rain)
    WeatherCondition.SNOW -> stringResource(R.string.condition_snow)
    WeatherCondition.SLEET -> stringResource(R.string.condition_sleet)
    WeatherCondition.THUNDERSTORM -> stringResource(R.string.condition_thunderstorm)
}

private val DAILY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
