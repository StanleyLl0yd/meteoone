#!/usr/bin/env python3
"""Verify that the first-class English and Russian Android resources stay compatible."""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

BASE_RESOURCES = Path("app/src/main/res/values/strings.xml")
RU_RESOURCES = Path("app/src/main/res/values-ru/strings.xml")
SUPPORTED_TYPES = {"string", "plurals"}
FORMAT_RE = re.compile(
    r"%(?:(?P<index>\d+)\$)?"
    r"[-#+ 0,(<]*"
    r"\d*"
    r"(?:\.\d+)?"
    r"(?P<conversion>[a-zA-Z])"
)


@dataclass(frozen=True)
class ResourceShape:
    kind: str
    placeholders: tuple[tuple[str, str], ...]


def _placeholder_signature(text: str) -> tuple[tuple[str, str], ...]:
    matches: list[tuple[str, str]] = []
    for match in FORMAT_RE.finditer(text):
        matches.append(
            (
                match.group("index") or "auto",
                match.group("conversion").lower(),
            )
        )
    return tuple(matches)


def _resource_text(element: ET.Element) -> str:
    return "".join(element.itertext())


def _read_resources(path: Path) -> dict[str, ResourceShape]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise ValueError(f"{path}: cannot parse Android resources: {error}") from error

    if root.tag != "resources":
        raise ValueError(f"{path}: root element must be <resources>")

    resources: dict[str, ResourceShape] = {}
    for element in root:
        if element.tag not in SUPPORTED_TYPES:
            continue

        name = element.attrib.get("name", "").strip()
        if not name:
            raise ValueError(f"{path}: <{element.tag}> resource without a name")
        if name in resources:
            raise ValueError(f"{path}: duplicate string/plural resource name {name!r}")

        if element.tag == "string":
            placeholders = _placeholder_signature(_resource_text(element))
        else:
            items = list(element.findall("item"))
            if not items:
                raise ValueError(f"{path}: plurals {name!r} has no <item> values")
            signatures = {
                _placeholder_signature(_resource_text(item))
                for item in items
            }
            if len(signatures) != 1:
                raise ValueError(
                    f"{path}: plurals {name!r} uses inconsistent format placeholders"
                )
            placeholders = signatures.pop()

        resources[name] = ResourceShape(
            kind=element.tag,
            placeholders=placeholders,
        )

    return resources


def verify_localization(root: Path = Path(".")) -> None:
    base_path = root / BASE_RESOURCES
    ru_path = root / RU_RESOURCES
    base = _read_resources(base_path)
    ru = _read_resources(ru_path)

    base_names = set(base)
    ru_names = set(ru)
    if base_names != ru_names:
        missing = sorted(base_names - ru_names)
        extra = sorted(ru_names - base_names)
        details: list[str] = []
        if missing:
            details.append("missing in RU: " + ", ".join(missing))
        if extra:
            details.append("extra in RU: " + ", ".join(extra))
        raise ValueError("Android localization key mismatch: " + "; ".join(details))

    mismatches: list[str] = []
    for name in sorted(base_names):
        base_shape = base[name]
        ru_shape = ru[name]
        if base_shape.kind != ru_shape.kind:
            mismatches.append(
                f"{name}: resource type {base_shape.kind} != {ru_shape.kind}"
            )
        elif base_shape.placeholders != ru_shape.placeholders:
            mismatches.append(
                f"{name}: format placeholders "
                f"{base_shape.placeholders} != {ru_shape.placeholders}"
            )
    if mismatches:
        raise ValueError(
            "Android localization shape mismatch:\n  " + "\n  ".join(mismatches)
        )


def main() -> None:
    try:
        verify_localization()
    except ValueError as error:
        raise SystemExit(str(error)) from error
    print("Android EN/RU localization parity: OK")


if __name__ == "__main__":
    main()
