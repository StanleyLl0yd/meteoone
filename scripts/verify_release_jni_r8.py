#!/usr/bin/env python3
"""Verify JNI keep rules survive AAR packaging and release R8 obfuscation."""

from __future__ import annotations

import argparse
from pathlib import Path
from zipfile import ZipFile

JNI_CLASSES = (
    "com.sl.meteoone.forecast.data.grib.EcCodesNativeBridge",
    "com.sl.meteoone.forecast.data.grib.NativeGribMessage",
)

REQUIRED_AAR_RULES = (
    "-keep class com.sl.meteoone.forecast.data.grib.EcCodesNativeBridge { *; }",
    "-keep class com.sl.meteoone.forecast.data.grib.NativeGribMessage {",
    "<init>(long[], double[], double[]);",
)


def verify_aar_consumer_rules(path: Path) -> None:
    with ZipFile(path) as archive:
        try:
            rules = archive.read("proguard.txt").decode("utf-8")
        except KeyError as exc:
            raise SystemExit(f"{path}: missing packaged consumer ProGuard rules (proguard.txt)") from exc

    compact = "\n".join(line.strip() for line in rules.splitlines() if line.strip())
    for required in REQUIRED_AAR_RULES:
        if required not in compact:
            raise SystemExit(f"{path}: packaged consumer rules are missing {required!r}")


def verify_release_mapping(path: Path) -> None:
    mappings: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if raw_line.startswith(" ") or " -> " not in raw_line or not raw_line.endswith(":"):
            continue
        original, renamed = raw_line[:-1].split(" -> ", 1)
        mappings[original] = renamed

    for class_name in JNI_CLASSES:
        renamed = mappings.get(class_name)
        if renamed is None:
            raise SystemExit(f"{path}: release mapping is missing JNI class {class_name}")
        if renamed != class_name:
            raise SystemExit(
                f"{path}: JNI class was obfuscated: {class_name} -> {renamed}"
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    args = parser.parse_args()

    verify_aar_consumer_rules(args.aar)
    verify_release_mapping(args.mapping)
    print("release JNI/R8 boundary verification: OK")


if __name__ == "__main__":
    main()
