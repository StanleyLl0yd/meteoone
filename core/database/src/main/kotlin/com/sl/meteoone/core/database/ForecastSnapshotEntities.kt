package com.sl.meteoone.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "forecast_snapshots")
internal data class ForecastSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "coordinate_key")
    val coordinateKey: String,
    @ColumnInfo(name = "latitude_tenths")
    val latitudeTenths: Int,
    @ColumnInfo(name = "longitude_tenths")
    val longitudeTenths: Int,
    @ColumnInfo(name = "generated_at_epoch_second")
    val generatedAtEpochSecond: Long,
    @ColumnInfo(name = "generated_at_nano")
    val generatedAtNano: Int,
    @ColumnInfo(name = "elevation_meters")
    val elevationMeters: Int?,
    @ColumnInfo(name = "time_zone_id")
    val timeZoneId: String,
)

@Entity(
    tableName = "forecast_hourly",
    primaryKeys = ["coordinate_key", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ForecastSnapshotEntity::class,
            parentColumns = ["coordinate_key"],
            childColumns = ["coordinate_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["coordinate_key"])],
)
internal data class ForecastHourlyEntity(
    @ColumnInfo(name = "coordinate_key")
    val coordinateKey: String,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "time_epoch_second")
    val timeEpochSecond: Long,
    @ColumnInfo(name = "time_nano")
    val timeNano: Int,
    @ColumnInfo(name = "temperature_c")
    val temperatureC: Double?,
    @ColumnInfo(name = "feels_like_c")
    val feelsLikeC: Double?,
    @ColumnInfo(name = "dew_point_c")
    val dewPointC: Double?,
    @ColumnInfo(name = "humidity_percent")
    val humidityPercent: Double?,
    @ColumnInfo(name = "pressure_sea_level_hpa")
    val pressureSeaLevelHpa: Double?,
    @ColumnInfo(name = "wind_speed_mps")
    val windSpeedMps: Double?,
    @ColumnInfo(name = "wind_gust_mps")
    val windGustMps: Double?,
    @ColumnInfo(name = "wind_direction_degrees")
    val windDirectionDegrees: Double?,
    @ColumnInfo(name = "precipitation_mm")
    val precipitationMm: Double?,
    @ColumnInfo(name = "precipitation_probability_percent")
    val precipitationProbabilityPercent: Double?,
    @ColumnInfo(name = "cloud_cover_percent")
    val cloudCoverPercent: Double?,
    @ColumnInfo(name = "visibility_meters")
    val visibilityMeters: Double?,
    @ColumnInfo(name = "condition")
    val condition: String,
    @ColumnInfo(name = "wind_gust_interval_start_epoch_second")
    val windGustIntervalStartEpochSecond: Long?,
    @ColumnInfo(name = "wind_gust_interval_start_nano")
    val windGustIntervalStartNano: Int?,
    @ColumnInfo(name = "precipitation_interval_start_epoch_second")
    val precipitationIntervalStartEpochSecond: Long?,
    @ColumnInfo(name = "precipitation_interval_start_nano")
    val precipitationIntervalStartNano: Int?,
    @ColumnInfo(name = "provider_count")
    val providerCount: Int,
    @ColumnInfo(name = "independent_evidence_count")
    val independentEvidenceCount: Int,
    @ColumnInfo(name = "agreement")
    val agreement: String,
)

@Entity(
    tableName = "forecast_sources",
    primaryKeys = ["coordinate_key", "provider", "model_family"],
    foreignKeys = [
        ForeignKey(
            entity = ForecastSnapshotEntity::class,
            parentColumns = ["coordinate_key"],
            childColumns = ["coordinate_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["coordinate_key"])],
)
internal data class ForecastSourceEntity(
    @ColumnInfo(name = "coordinate_key")
    val coordinateKey: String,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "model_family")
    val modelFamily: String,
    @ColumnInfo(name = "model_run_epoch_second")
    val modelRunEpochSecond: Long?,
    @ColumnInfo(name = "model_run_nano")
    val modelRunNano: Int?,
    @ColumnInfo(name = "generated_at_epoch_second")
    val generatedAtEpochSecond: Long,
    @ColumnInfo(name = "generated_at_nano")
    val generatedAtNano: Int,
)

@Entity(
    tableName = "forecast_source_hourly",
    primaryKeys = ["coordinate_key", "provider", "model_family", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ForecastSourceEntity::class,
            parentColumns = ["coordinate_key", "provider", "model_family"],
            childColumns = ["coordinate_key", "provider", "model_family"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["coordinate_key", "provider", "model_family"])],
)
internal data class ForecastSourceHourlyEntity(
    @ColumnInfo(name = "coordinate_key")
    val coordinateKey: String,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "model_family")
    val modelFamily: String,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "time_epoch_second")
    val timeEpochSecond: Long,
    @ColumnInfo(name = "time_nano")
    val timeNano: Int,
    @ColumnInfo(name = "temperature_c")
    val temperatureC: Double?,
    @ColumnInfo(name = "feels_like_c")
    val feelsLikeC: Double?,
    @ColumnInfo(name = "dew_point_c")
    val dewPointC: Double?,
    @ColumnInfo(name = "humidity_percent")
    val humidityPercent: Double?,
    @ColumnInfo(name = "pressure_sea_level_hpa")
    val pressureSeaLevelHpa: Double?,
    @ColumnInfo(name = "wind_speed_mps")
    val windSpeedMps: Double?,
    @ColumnInfo(name = "wind_gust_mps")
    val windGustMps: Double?,
    @ColumnInfo(name = "wind_direction_degrees")
    val windDirectionDegrees: Double?,
    @ColumnInfo(name = "precipitation_mm")
    val precipitationMm: Double?,
    @ColumnInfo(name = "precipitation_probability_percent")
    val precipitationProbabilityPercent: Double?,
    @ColumnInfo(name = "cloud_cover_percent")
    val cloudCoverPercent: Double?,
    @ColumnInfo(name = "visibility_meters")
    val visibilityMeters: Double?,
    @ColumnInfo(name = "condition")
    val condition: String,
    @ColumnInfo(name = "wind_gust_interval_start_epoch_second")
    val windGustIntervalStartEpochSecond: Long?,
    @ColumnInfo(name = "wind_gust_interval_start_nano")
    val windGustIntervalStartNano: Int?,
    @ColumnInfo(name = "precipitation_interval_start_epoch_second")
    val precipitationIntervalStartEpochSecond: Long?,
    @ColumnInfo(name = "precipitation_interval_start_nano")
    val precipitationIntervalStartNano: Int?,
)

@Entity(
    tableName = "forecast_failed_sources",
    primaryKeys = ["coordinate_key", "provider", "model_family"],
    foreignKeys = [
        ForeignKey(
            entity = ForecastSnapshotEntity::class,
            parentColumns = ["coordinate_key"],
            childColumns = ["coordinate_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["coordinate_key"])],
)
internal data class ForecastFailedSourceEntity(
    @ColumnInfo(name = "coordinate_key")
    val coordinateKey: String,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "model_family")
    val modelFamily: String,
)

internal data class ForecastSnapshotWithEvidence(
    @Embedded
    val snapshot: ForecastSnapshotEntity,
    @Relation(
        parentColumn = "coordinate_key",
        entityColumn = "coordinate_key",
    )
    val hourly: List<ForecastHourlyEntity>,
    @Relation(
        parentColumn = "coordinate_key",
        entityColumn = "coordinate_key",
    )
    val sources: List<ForecastSourceEntity>,
    @Relation(
        parentColumn = "coordinate_key",
        entityColumn = "coordinate_key",
    )
    val sourceHourly: List<ForecastSourceHourlyEntity>,
    @Relation(
        parentColumn = "coordinate_key",
        entityColumn = "coordinate_key",
    )
    val failedSources: List<ForecastFailedSourceEntity>,
)
