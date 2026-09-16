#!/usr/bin/env python3
"""Write a deterministic SHA-256 manifest for explicit release artifacts."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(paths: list[Path]) -> str:
    if not paths:
        raise ValueError("at least one artifact is required")

    normalized: list[Path] = []
    seen_names: set[str] = set()
    for path in paths:
        if path.is_symlink():
            raise ValueError(f"refusing symlink artifact: {path}")
        if not path.is_file():
            raise ValueError(f"artifact is not a regular file: {path}")
        name = path.name
        if not name or "\n" in name or "\r" in name:
            raise ValueError(f"unsafe artifact filename: {path}")
        if name in seen_names:
            raise ValueError(f"duplicate artifact filename: {name}")
        seen_names.add(name)
        normalized.append(path)

    lines = [f"{sha256_file(path)}  {path.name}" for path in sorted(normalized, key=lambda item: item.name)]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifacts", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, default=Path("SHA256SUMS"))
    args = parser.parse_args()

    try:
        manifest = build_manifest(args.artifacts)
        if args.output.is_symlink():
            raise ValueError(f"refusing symlink output: {args.output}")
        args.output.write_text(manifest, encoding="utf-8", newline="\n")
    except (OSError, ValueError) as error:
        raise SystemExit(f"SHA-256 manifest generation failed: {error}") from error

    print(f"Wrote {args.output} for {len(args.artifacts)} artifact(s)")


if __name__ == "__main__":
    main()
