package com.sl.meteoone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsContent(
    modifier: Modifier,
) {
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsSectionHeading(
                title = stringResource(R.string.settings_about_title),
                body = stringResource(
                    R.string.settings_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
            )
        }

        item {
            SettingsCard(
                title = stringResource(R.string.settings_privacy_title),
                body = stringResource(R.string.settings_privacy_body),
            ) {
                SettingsLink(
                    label = stringResource(R.string.settings_open_privacy_policy),
                    onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                )
            }
        }

        item {
            SettingsCard(
                title = stringResource(R.string.settings_offline_title),
                body = stringResource(R.string.settings_offline_body),
            )
        }

        item {
            SettingsSectionHeading(
                title = stringResource(R.string.settings_sources_title),
                body = stringResource(R.string.settings_sources_explanation),
            )
        }

        item {
            SettingsSourceCard(
                title = stringResource(R.string.settings_source_open_meteo_title),
                body = stringResource(R.string.settings_source_open_meteo_body),
                linkLabel = stringResource(R.string.settings_source_details),
                onOpen = { uriHandler.openUri(OPEN_METEO_URL) },
            )
        }

        item {
            SettingsSourceCard(
                title = stringResource(R.string.settings_source_noaa_title),
                body = stringResource(R.string.settings_source_noaa_body),
                linkLabel = stringResource(R.string.settings_source_details),
                onOpen = { uriHandler.openUri(NOAA_NOMADS_URL) },
            )
        }

        item {
            SettingsSourceCard(
                title = stringResource(R.string.settings_source_ecmwf_title),
                body = stringResource(R.string.settings_source_ecmwf_body),
                linkLabel = stringResource(R.string.settings_source_details),
                onOpen = { uriHandler.openUri(ECMWF_OPEN_DATA_URL) },
            )
        }

        item {
            SettingsSourceCard(
                title = stringResource(R.string.settings_source_dwd_title),
                body = stringResource(R.string.settings_source_dwd_body),
                linkLabel = stringResource(R.string.settings_source_details),
                onOpen = { uriHandler.openUri(DWD_OPEN_DATA_URL) },
            )
        }

        item {
            SettingsCard(
                title = stringResource(R.string.settings_project_title),
                body = stringResource(R.string.settings_project_body),
            ) {
                SettingsLink(
                    label = stringResource(R.string.settings_open_project),
                    onClick = { uriHandler.openUri(PROJECT_URL) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeading(
    title: String,
    body: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    body: String,
    content: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 6.dp),
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
            content?.invoke()
        }
    }
}

@Composable
private fun SettingsSourceCard(
    title: String,
    body: String,
    linkLabel: String,
    onOpen: () -> Unit,
) {
    SettingsCard(
        title = title,
        body = body,
    ) {
        SettingsLink(
            label = linkLabel,
            onClick = onOpen,
        )
    }
}

@Composable
private fun SettingsLink(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier.padding(top = 4.dp),
        onClick = onClick,
    ) {
        Text(label)
    }
}

private const val PROJECT_URL = "https://github.com/StanleyLl0yd/meteoone"
private const val PRIVACY_POLICY_URL =
    "https://github.com/StanleyLl0yd/meteoone/blob/main/PRIVACY.md"
private const val OPEN_METEO_URL = "https://open-meteo.com/"
private const val NOAA_NOMADS_URL = "https://nomads.ncep.noaa.gov/"
private const val ECMWF_OPEN_DATA_URL = "https://www.ecmwf.int/en/forecasts/datasets/open-data"
private const val DWD_OPEN_DATA_URL = "https://opendata.dwd.de/"
