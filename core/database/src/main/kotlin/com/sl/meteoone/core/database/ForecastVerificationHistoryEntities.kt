package com.sl.meteoone.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "verification_forecast_runs",
    primaryKeys = [
        "coordinate_key",
        "provider",
        "model_family",
        "model_run_epoch_second",
        "model_run_nano",
    ],
    indices = [
        Index(value = ["coordinate_key"]),
        Index(value = ["model_run_epoch_second", "model_run_nano"]),
    ],
)
internal data class VerificationForecastRunEntity(
    @ColumnInfo(name = "coordinate_key")
    val coordinateKey: String,
    @ColumnInfo(name = "latitude_tenths")
    val latitudeTenths: Int,
    @ColumnInfo(name = "longitude_tenths")
    val longitudeTenths: Int,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "model_family")
    val modelFamily: String,
    @ColumnInfo(name = "model_run_epoch_second")
    val modelRunEpochSecond: Long,
    @ColumnInfo(name = "model_run_nano")
    val modelRunNano: Int,
    @ColumnInfo(name = "first_captured_at_epoch_second")
    val firstCapturedAtEpochSecond: Long,
    @ColumnInfo(name = "first_captured_at_nano")
    val firstCapturedAtNano: Int,
    @ColumnInfo(name = "elevation_meters")
    val elevationMeters: Int?,
    @ColumnInfo(name = "time_zone_id")
    val timeZoneId: String,
)

@Entity(
    tableName = "verification_forecast_hourly",
    primaryKeys = [
        "coordinate_key",
        "provider",
        "model_family",
        "model_run_epoch_second",
        "model_run_nano",
        "valid_time_epoch_second",
        "valid_time_nano",
    ],
    foreignKeys = [
        ForeignKey(
            entity = VerificationForecastRunEntity::class,
            parentColumns = [
                "coordinate_key",
                "provider",
                "model_family",
                "model_run_epoch_second",
                "model_run_nano",
            ],
            childColumns = [
                "coordinate_key",
                "provider",
                "model_family",
                "model_run_epoch_second",
                "model_run_nano",
            ],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = [
                "coordinate_key",
                "provider",
                "model_family",
                "model_run_epoch_second",
                "model_run_nano",
            ],
        ),
        Index(value = ["valid_time_epoch_second", "valid_time_nano"]),
    ],
)
internal data class VerificationForecastHourlyEntity(
    @ColumnInfo(name = "coordinate_key")
    val coordinateKey: String,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "model_family")
    val modelFamily: String,
    @ColumnInfo(name = "model_run_epoch_second")
    val modelRunEpochSecond: Long,
    @ColumnInfo(name = "model_run_nano")
    val modelRunNano: Int,
    @ColumnInfo(name = "valid_time_epoch_second")
    val validTimeEpochSecond: Long,
    @ColumnInfo(name = "valid_time_nano")
    val validTimeNano: Int,
    @ColumnInfo(name = "lead_seconds")
    val leadSeconds: Long,
    @ColumnInfo(name = "lead_nano")
    val leadNano: Int,
    @ColumnInfo(name = "temperature_c")
    val temperatureC: Double?,
    @ColumnInfo(name = "pressure_sea_level_hpa")
    val pressureSeaLevelHpa: Double?,
    @ColumnInfo(name = "wind_speed_mps")
    val windSpeedMps: Double?,
    @ColumnInfo(name = "wind_direction_degrees")
    val windDirectionDegrees: Double?,
    @ColumnInfo(name = "precipitation_mm")
    val precipitationMm: Double?,
    @ColumnInfo(name = "precipitation_interval_start_epoch_second")
    val precipitationIntervalStartEpochSecond: Long?,
    @ColumnInfo(name = "precipitation_interval_start_nano")
    val precipitationIntervalStartNano: Int?,
)
