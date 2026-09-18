#!/usr/bin/env python3
"""Verify that the first-class English and Russian Android resources stay compatible."""

from __future__ import annotations

import re
from html import unescape
from dataclasses import dataclass
from pathlib import Path

BASE_RESOURCES = Path("app/src/main/res/values/strings.xml")
RU_RESOURCES = Path("app/src/main/res/values-ru/strings.xml")
SUPPORTED_TYPES = {"string", "plurals"}
DOCTYPE_OR_ENTITY_RE = re.compile(r"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE)
RESOURCE_RE = re.compile(
    r"<(?P<kind>string|plurals)\b(?P<attributes>[^>]*)>"
    r"(?P<body>.*?)</(?P=kind)>",
    re.DOTALL,
)
NAME_RE = re.compile(
    r"\bname\s*=\s*(?P<quote>['\"])(?P<name>.*?)(?P=quote)",
    re.DOTALL,
)
ITEM_RE = re.compile(
    r"<item\b(?P<attributes>[^>]*)>(?P<body>.*?)</item>",
    re.DOTALL,
)
TAG_RE = re.compile(r"<[^>]+>")
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


def _plain_text(fragment: str) -> str:
    return unescape(TAG_RE.sub("", fragment))


def _read_resources(path: Path) -> dict[str, ResourceShape]:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise ValueError(f"{path}: cannot read Android resources: {error}") from error

    if DOCTYPE_OR_ENTITY_RE.search(text):
        raise ValueError(f"{path}: DTD/entity declarations are not allowed")
    if "<resources" not in text or "</resources>" not in text:
        raise ValueError(f"{path}: root <resources> element is missing")

    opening_count = len(re.findall(r"<(?:string|plurals)\b", text))
    matches = list(RESOURCE_RE.finditer(text))
    if len(matches) != opening_count:
        raise ValueError(f"{path}: malformed string/plural resource markup")

    resources: dict[str, ResourceShape] = {}
    for match in matches:
        kind = match.group("kind")
        if kind not in SUPPORTED_TYPES:
            continue

        name_match = NAME_RE.search(match.group("attributes"))
        name = unescape(name_match.group("name")).strip() if name_match else ""
        if not name:
            raise ValueError(f"{path}: <{kind}> resource without a name")
        if name in resources:
            raise ValueError(f"{path}: duplicate string/plural resource name {name!r}")

        body = match.group("body")
        if kind == "string":
            placeholders = _placeholder_signature(_plain_text(body))
        else:
            items = list(ITEM_RE.finditer(body))
            if not items:
                raise ValueError(f"{path}: plurals {name!r} has no <item> values")
            signatures = {
                _placeholder_signature(_plain_text(item.group("body")))
                for item in items
            }
            if len(signatures) != 1:
                raise ValueError(
                    f"{path}: plurals {name!r} uses inconsistent format placeholders"
                )
            placeholders = signatures.pop()

        resources[name] = ResourceShape(
            kind=kind,
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
