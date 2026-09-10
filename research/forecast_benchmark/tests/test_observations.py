from __future__ import annotations

import unittest
from datetime import date

from research.forecast_benchmark.model import Location
from research.forecast_benchmark.observations import (
    TextHttpClient,
    parse_global_hourly,
    parse_station_history,
    select_station,
)


LOCATION = Location(
    id="test",
    name="Test",
    latitude=59.94,
    longitude=30.31,
    timezone_id="Europe/Moscow",
    climate_note="fixture",
)


class TextHttpClientSecurityTest(unittest.TestCase):
    def test_rejects_non_https_and_unapproved_hosts(self) -> None:
        client = TextHttpClient()
        with self.assertRaises(ValueError):
            client.get_text("file:///etc/passwd")
        with self.assertRaises(ValueError):
            client.get_text("https://example.test/data.csv")


class StationSelectionTest(unittest.TestCase):
    def test_selects_nearest_station_covering_full_period(self) -> None:
        stations = parse_station_history(
            """USAF,WBAN,STATION NAME,CTRY,STATE,ICAO,LAT,LON,ELEV(M),BEGIN,END
260630,99999,NEAR,RU,,ULLI,+59.800,+030.300,+0024.0,20000101,20301231
260640,99999,FAR,RU,,ULLX,+59.000,+030.000,+0030.0,20000101,20301231
260650,99999,ENDED,RU,,ULLY,+59.900,+030.300,+0025.0,20000101,20200101
"""
        )
        match = select_station(
            LOCATION,
            stations,
            date(2026, 8, 1),
            date(2026, 8, 31),
            max_distance_km=75,
        )
        self.assertEqual(match.station.station_id, "26063099999")
        self.assertLess(match.distance_km, 20)

    def test_elevation_limit_rejects_nearest_mismatch(self) -> None:
        stations = parse_station_history(
            """USAF,WBAN,STATION NAME,CTRY,STATE,ICAO,LAT,LON,ELEV(M),BEGIN,END
260630,99999,NEAR-HIGH,RU,,ULLI,+59.930,+030.310,+0600.0,20000101,20301231
260640,99999,FAR-LOW,RU,,ULLX,+59.800,+030.310,+0030.0,20000101,20301231
"""
        )
        match = select_station(
            LOCATION,
            stations,
            date(2026, 8, 1),
            date(2026, 8, 31),
            target_elevation_m=20,
            max_elevation_delta_m=100,
        )
        self.assertEqual(match.station.station_id, "26064099999")


class GlobalHourlyParserTest(unittest.TestCase):
    def test_parses_good_values_and_hourly_precipitation(self) -> None:
        text = """STATION,DATE,WND,TMP,SLP,AA1
26063099999,2026-09-01T00:00:00,"350,1,N,0042,1","+0125,1","10124,1","01,0004,9,1"
"""
        point = parse_global_hourly(text, "26063099999")[0]
        self.assertEqual(point.time, "2026-09-01T00:00:00Z")
        self.assertEqual(point.temperature_c, 12.5)
        self.assertEqual(point.pressure_sea_level_hpa, 1012.4)
        self.assertEqual(point.wind_speed_mps, 4.2)
        self.assertEqual(point.wind_direction_degrees, 350.0)
        self.assertEqual(point.precipitation_mm, 0.4)
        self.assertFalse(point.precipitation_trace)

    def test_rejects_suspect_values_and_preserves_trace(self) -> None:
        text = """STATION,DATE,WND,TMP,SLP,AA1,AA2
26063099999,2026-09-01T01:00:00,"999,9,N,0000,1","+0200,2","99999,9","03,0010,9,1","01,0000,2,1"
"""
        point = parse_global_hourly(text, "26063099999")[0]
        self.assertIsNone(point.temperature_c)
        self.assertIsNone(point.pressure_sea_level_hpa)
        self.assertEqual(point.wind_speed_mps, 0.0)
        self.assertEqual(point.wind_direction_degrees, 0.0)
        self.assertEqual(point.precipitation_mm, 0.0)
        self.assertTrue(point.precipitation_trace)

    def test_duplicate_timestamp_keeps_more_complete_record(self) -> None:
        text = """STATION,DATE,WND,TMP,SLP
26063099999,2026-09-01T00:00:00,,"+0100,1",
26063099999,2026-09-01T00:00:00,"180,1,N,0030,1","+0110,1","10000,1"
"""
        point = parse_global_hourly(text, "26063099999")[0]
        self.assertEqual(point.temperature_c, 11.0)
        self.assertEqual(point.pressure_sea_level_hpa, 1000.0)
        self.assertEqual(point.wind_speed_mps, 3.0)


if __name__ == "__main__":
    unittest.main()
