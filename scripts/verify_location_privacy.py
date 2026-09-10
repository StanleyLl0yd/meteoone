#!/usr/bin/env python3
"""Reject repository changes that weaken the current location privacy boundary."""

from __future__ import annotations

import re
from html import unescape
from pathlib import Path

ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
COARSE = "android.permission.ACCESS_COARSE_LOCATION"
FORBIDDEN = {
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
}
LOCATION_MANIFEST = Path("core/location/src/main/AndroidManifest.xml")
LOCATION_SOURCE_ROOT = Path("core/location/src/main")
DOCTYPE_OR_ENTITY_RE = re.compile(r"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE)
XMLNS_RE = re.compile(
    r"\bxmlns:(?P<prefix>[A-Za-z_][\w.-]*)\s*=\s*"
    r"(?P<quote>['\"])(?P<value>.*?)(?P=quote)",
    re.DOTALL,
)
PERMISSION_TAG_RE = re.compile(
    r"<uses-permission(?:-sdk-23)?\b"
    r"(?P<attributes>(?:[^>'\"]|'[^']*'|\"[^\"]*\")*)>",
    re.DOTALL,
)
ATTRIBUTE_RE = re.compile(
    r"(?P<name>[A-Za-z_][\w.:-]*)\s*=\s*"
    r"(?P<quote>['\"])(?P<value>.*?)(?P=quote)",
    re.DOTALL,
)


def manifest_permissions(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    if DOCTYPE_OR_ENTITY_RE.search(text):
        raise SystemExit(f"{path}: DTD/entity declarations are not allowed")

    android_prefixes = {
        match.group("prefix")
        for match in XMLNS_RE.finditer(text)
        if unescape(match.group("value")) == ANDROID_NAMESPACE
    }
    android_name_attributes = {f"{prefix}:name" for prefix in android_prefixes}

    permissions: set[str] = set()
    for tag in PERMISSION_TAG_RE.finditer(text):
        for attribute in ATTRIBUTE_RE.finditer(tag.group("attributes")):
            if attribute.group("name") in android_name_attributes:
                permissions.add(unescape(attribute.group("value")))
                break
    return permissions


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
