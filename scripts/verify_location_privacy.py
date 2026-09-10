#!/usr/bin/env python3
"""Reject repository changes that weaken the current location privacy boundary."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
COARSE = "android.permission.ACCESS_COARSE_LOCATION"
FORBIDDEN = {
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
}
LOCATION_MANIFEST = Path("core/location/src/main/AndroidManifest.xml")
LOCATION_SOURCE_ROOT = Path("core/location/src/main")


def manifest_permissions(path: Path) -> set[str]:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        raise SystemExit(f"{path}: invalid Android manifest XML: {error}") from error

    return {
        value
        for element in root.findall("uses-permission")
        if (value := element.get(ANDROID_NAME)) is not None
    }


def main() -> None:
    manifests = sorted(Path(".").glob("**/src/main/AndroidManifest.xml"))
    if LOCATION_MANIFEST not in manifests:
        raise SystemExit(f"{LOCATION_MANIFEST}: required location manifest is missing")

    coarse_owners: list[Path] = []
    for manifest in manifests:
        permissions = manifest_permissions(manifest)
        forbidden = permissions & FORBIDDEN
        if forbidden:
            raise SystemExit(
                f"{manifest}: forbidden location permission(s): "
                f"{', '.join(sorted(forbidden))}"
            )
        if COARSE in permissions:
            coarse_owners.append(manifest)

    if coarse_owners != [LOCATION_MANIFEST]:
        rendered = ", ".join(str(path) for path in coarse_owners) or "none"
        raise SystemExit(
            f"{COARSE}: must be owned only by {LOCATION_MANIFEST}; found {rendered}"
        )

    for source in sorted(Path(".").glob("**/src/main/**/*.kt")):
        if LOCATION_SOURCE_ROOT in source.parents:
            continue
        text = source.read_text(encoding="utf-8")
        if "android.location." in text:
            raise SystemExit(
                f"{source}: android.location APIs must remain inside :core:location"
            )

    print("Location privacy boundary: OK")


if __name__ == "__main__":
    main()
