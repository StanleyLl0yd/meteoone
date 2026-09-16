#!/usr/bin/env python3
"""Verify Android release identity and version metadata."""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path

DEFAULT_BUILD_FILE = Path("app/build.gradle.kts")
EXPECTED_APPLICATION_ID = "com.sl.meteoone"
VERSION_NAME_RE = re.compile(
    r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$"
)


@dataclass(frozen=True)
class ReleaseMetadata:
    namespace: str
    application_id: str
    version_code: int
    version_name: str


def _extract_unique(text: str, pattern: str, field: str) -> str:
    matches = re.findall(pattern, text, flags=re.MULTILINE)
    if len(matches) != 1:
        raise ValueError(f"expected exactly one {field}, found {len(matches)}")
    return matches[0]


def parse_release_metadata(text: str) -> ReleaseMetadata:
    namespace = _extract_unique(
        text,
        r'^\s*namespace\s*=\s*"([^"]+)"\s*$',
        "namespace",
    )
    application_id = _extract_unique(
        text,
        r'^\s*applicationId\s*=\s*"([^"]+)"\s*$',
        "applicationId",
    )
    version_code_text = _extract_unique(
        text,
        r"^\s*versionCode\s*=\s*([0-9]+)\s*$",
        "versionCode",
    )
    version_name = _extract_unique(
        text,
        r'^\s*versionName\s*=\s*"([^"]+)"\s*$',
        "versionName",
    )
    return ReleaseMetadata(
        namespace=namespace,
        application_id=application_id,
        version_code=int(version_code_text),
        version_name=version_name,
    )


def verify_release_metadata(
    metadata: ReleaseMetadata,
    *,
    expected_version_name: str | None = None,
    expected_version_code: int | None = None,
) -> None:
    if metadata.namespace != EXPECTED_APPLICATION_ID:
        raise ValueError(
            f"namespace must remain {EXPECTED_APPLICATION_ID}, got {metadata.namespace}"
        )
    if metadata.application_id != EXPECTED_APPLICATION_ID:
        raise ValueError(
            "applicationId must remain "
            f"{EXPECTED_APPLICATION_ID}, got {metadata.application_id}"
        )
    if metadata.version_code <= 0:
        raise ValueError("versionCode must be a positive integer")
    if not VERSION_NAME_RE.fullmatch(metadata.version_name):
        raise ValueError(
            "versionName must use numeric major.minor.patch with an optional "
            "pre-release suffix"
        )
    if expected_version_name is not None and metadata.version_name != expected_version_name:
        raise ValueError(
            f"versionName must be {expected_version_name}, got {metadata.version_name}"
        )
    if expected_version_code is not None and metadata.version_code != expected_version_code:
        raise ValueError(
            f"versionCode must be {expected_version_code}, got {metadata.version_code}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-file", type=Path, default=DEFAULT_BUILD_FILE)
    parser.add_argument("--expected-version-name")
    parser.add_argument("--expected-version-code", type=int)
    args = parser.parse_args()

    try:
        metadata = parse_release_metadata(args.build_file.read_text(encoding="utf-8"))
        verify_release_metadata(
            metadata,
            expected_version_name=args.expected_version_name,
            expected_version_code=args.expected_version_code,
        )
    except (OSError, ValueError) as error:
        raise SystemExit(f"Release metadata verification failed: {error}") from error

    print(
        "Release metadata: OK "
        f"({metadata.application_id} {metadata.version_name} code={metadata.version_code})"
    )


if __name__ == "__main__":
    main()
