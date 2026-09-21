package com.sl.meteoone.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "verification_observation_stations",
    primaryKeys = ["source_id", "station_id"],
)
internal data class VerificationObservationStationEntity(
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "station_id")
    val stationId: String,
    @ColumnInfo(name = "latitude")
    val latitude: Double,
    @ColumnInfo(name = "longitude")
    val longitude: Double,
    @ColumnInfo(name = "elevation_meters")
    val elevationMeters: Double?,
)

@Entity(
    tableName = "verification_surface_observations",
    primaryKeys = [
        "source_id",
        "station_id",
        "observed_at_epoch_second",
        "observed_at_nano",
    ],
    foreignKeys = [
        ForeignKey(
            entity = VerificationObservationStationEntity::class,
            parentColumns = ["source_id", "station_id"],
            childColumns = ["source_id", "station_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id", "station_id"]),
        Index(value = ["observed_at_epoch_second", "observed_at_nano"]),
    ],
)
internal data class VerificationSurfaceObservationEntity(
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "station_id")
    val stationId: String,
    @ColumnInfo(name = "observed_at_epoch_second")
    val observedAtEpochSecond: Long,
    @ColumnInfo(name = "observed_at_nano")
    val observedAtNano: Int,
    @ColumnInfo(name = "temperature_c")
    val temperatureC: Double?,
    @ColumnInfo(name = "pressure_sea_level_hpa")
    val pressureSeaLevelHpa: Double?,
    @ColumnInfo(name = "wind_speed_mps")
    val windSpeedMps: Double?,
    @ColumnInfo(name = "wind_direction_degrees")
    val windDirectionDegrees: Double?,
)

@Entity(
    tableName = "verification_precipitation_observations",
    primaryKeys = [
        "source_id",
        "station_id",
        "interval_start_epoch_second",
        "interval_start_nano",
        "interval_end_epoch_second",
        "interval_end_nano",
    ],
    foreignKeys = [
        ForeignKey(
            entity = VerificationObservationStationEntity::class,
            parentColumns = ["source_id", "station_id"],
            childColumns = ["source_id", "station_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id", "station_id"]),
        Index(value = ["interval_end_epoch_second", "interval_end_nano"]),
    ],
)
internal data class VerificationPrecipitationObservationEntity(
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "station_id")
    val stationId: String,
    @ColumnInfo(name = "interval_start_epoch_second")
    val intervalStartEpochSecond: Long,
    @ColumnInfo(name = "interval_start_nano")
    val intervalStartNano: Int,
    @ColumnInfo(name = "interval_end_epoch_second")
    val intervalEndEpochSecond: Long,
    @ColumnInfo(name = "interval_end_nano")
    val intervalEndNano: Int,
    @ColumnInfo(name = "amount_mm")
    val amountMm: Double,
)
