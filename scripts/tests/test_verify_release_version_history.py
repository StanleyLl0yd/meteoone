from __future__ import annotations

import unittest

from scripts.verify_release_metadata import ReleaseMetadata
from scripts.verify_release_version_history import (
    TaggedRelease,
    verify_monotonic_version_code,
)


def metadata(version_name: str, version_code: int) -> ReleaseMetadata:
    return ReleaseMetadata(
        namespace="com.sl.meteoone",
        application_id="com.sl.meteoone",
        version_code=version_code,
        version_name=version_name,
    )


class ReleaseVersionHistoryTest(unittest.TestCase):
    def test_accepts_first_release(self) -> None:
        verify_monotonic_version_code(metadata("0.1.0-alpha.1", 1), [])

    def test_accepts_strictly_increasing_version_code(self) -> None:
        verify_monotonic_version_code(
            metadata("0.3.0-alpha.1", 3),
            [
                TaggedRelease("v0.1.0-alpha.1", metadata("0.1.0-alpha.1", 1)),
                TaggedRelease("v0.2.0-alpha.1", metadata("0.2.0-alpha.1", 2)),
            ],
        )

    def test_rejects_reused_or_lower_version_code(self) -> None:
        history = [
            TaggedRelease("v0.2.0-alpha.1", metadata("0.2.0-alpha.1", 2)),
        ]
        for invalid_code in (1, 2):
            with self.subTest(invalid_code=invalid_code):
                with self.assertRaisesRegex(ValueError, "must be greater than prior maximum"):
                    verify_monotonic_version_code(
                        metadata("0.3.0-alpha.1", invalid_code),
                        history,
                    )

    def test_rejects_existing_version_name_even_with_higher_code(self) -> None:
        with self.assertRaisesRegex(ValueError, "already exists"):
            verify_monotonic_version_code(
                metadata("0.2.0-alpha.1", 3),
                [
                    TaggedRelease(
                        "v0.2.0-alpha.1",
                        metadata("0.2.0-alpha.1", 2),
                    ),
                ],
            )


if __name__ == "__main__":
    unittest.main()
