#!/usr/bin/env python3
"""Verify the canonical MeteoOne icon without rewriting any raster bytes."""

from __future__ import annotations

import hashlib
import struct
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
CANONICAL = Path("docs/branding/assets/meteoone-icon-canonical-1254.png")
CANONICAL_SHA256 = "b9c404a92148203497f021de7fcd6ef473ce59ee28cb449c251a04f448bdb627"

EXPECTED_DIMENSIONS = {
    CANONICAL: (1254, 1254),
    Path("docs/branding/assets/meteoone-icon-store-512.png"): (512, 512),
    Path("app/src/main/res/mipmap-mdpi/ic_launcher.png"): (48, 48),
    Path("app/src/main/res/mipmap-hdpi/ic_launcher.png"): (72, 72),
    Path("app/src/main/res/mipmap-xhdpi/ic_launcher.png"): (96, 96),
    Path("app/src/main/res/mipmap-xxhdpi/ic_launcher.png"): (144, 144),
    Path("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"): (192, 192),
}


def png_dimensions(path: Path) -> tuple[int, int]:
    with path.open("rb") as stream:
        header = stream.read(24)
    if len(header) != 24 or header[:8] != PNG_SIGNATURE or header[12:16] != b"IHDR":
        raise ValueError(f"{path}: invalid PNG header")
    return struct.unpack(">II", header[16:24])


def main() -> None:
    canonical_hash = hashlib.sha256(CANONICAL.read_bytes()).hexdigest()
    if canonical_hash != CANONICAL_SHA256:
        raise SystemExit(
            f"{CANONICAL}: canonical byte hash changed: {canonical_hash}"
        )

    for path, expected in EXPECTED_DIMENSIONS.items():
        if not path.is_file():
            raise SystemExit(f"{path}: required icon raster is missing")
        actual = png_dimensions(path)
        if actual != expected:
            raise SystemExit(
                f"{path}: expected {expected[0]}x{expected[1]}, "
                f"got {actual[0]}x{actual[1]}"
            )

    print("Canonical app icon integrity: OK")


if __name__ == "__main__":
    main()
