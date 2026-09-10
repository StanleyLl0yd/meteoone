from __future__ import annotations

import hashlib
import unittest
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from research.forecast_benchmark.cli import load_locations


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCATIONS = ROOT / "locations.json"
M0_RUSSIA_LOCATIONS = ROOT / "locations_m0_russia.json"
M0_RUSSIA_SHA256 = "42a3306a2e096bff1eec7bfece17b930a85cdc8594528c0193d780c63dd3c030"

EXPECTED_M0_IDS = {
    "saint-petersburg",
    "moscow",
    "kazan",
    "yekaterinburg",
    "novosibirsk",
    "krasnoyarsk",
    "sochi",
    "vladivostok",
    "yakutsk",
    "murmansk",
}

EXPECTED_CURRENT_RUSSIA_IDS = {
    "saint-petersburg",
    "moscow",
    "kazan",
    "novosibirsk",
    "vladivostok",
}

EXPECTED_GLOBAL_IDS = {
    "tbilisi",
    "yerevan",
    "tokyo",
    "new-york",
    "paris",
    "berlin",
    "dubai",
}


class LocationSetTest(unittest.TestCase):
    def test_default_verification_set_contains_selected_russia_and_global_locations(self) -> None:
        locations = load_locations(DEFAULT_LOCATIONS)
        ids = {location.id for location in locations}

        self.assertEqual(len(locations), 12)
        self.assertEqual(len(ids), len(locations))
        self.assertEqual(ids, EXPECTED_CURRENT_RUSSIA_IDS | EXPECTED_GLOBAL_IDS)
        self.assertTrue(EXPECTED_CURRENT_RUSSIA_IDS.issubset(EXPECTED_M0_IDS))

    def test_m0_russia_set_is_preserved_byte_for_byte(self) -> None:
        digest = hashlib.sha256(M0_RUSSIA_LOCATIONS.read_bytes()).hexdigest()
        self.assertEqual(digest, M0_RUSSIA_SHA256)

        locations = load_locations(M0_RUSSIA_LOCATIONS)
        self.assertEqual(len(locations), 10)
        self.assertEqual({location.id for location in locations}, EXPECTED_M0_IDS)

    def test_location_coordinates_and_timezones_are_valid(self) -> None:
        for location in load_locations(DEFAULT_LOCATIONS):
            with self.subTest(location=location.id):
                self.assertGreaterEqual(location.latitude, -90.0)
                self.assertLessEqual(location.latitude, 90.0)
                self.assertGreaterEqual(location.longitude, -180.0)
                self.assertLessEqual(location.longitude, 180.0)
                self.assertTrue(location.id.strip())
                self.assertTrue(location.name.strip())
                try:
                    ZoneInfo(location.timezone_id)
                except ZoneInfoNotFoundError as error:
                    self.fail(
                        f"Unknown IANA timezone for {location.id}: "
                        f"{location.timezone_id}: {error}"
                    )


if __name__ == "__main__":
    unittest.main()
