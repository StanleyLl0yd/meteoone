#!/usr/bin/env python3
"""Fail a release when its Android version code is not monotonic across release tags."""

from __future__ import annotations

import argparse
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from scripts.verify_release_metadata import (
    ReleaseMetadata,
    VERSION_NAME_RE,
    parse_release_metadata,
)

RELEASE_TAG_RE = re.compile(r"^v(?P<version>.+)$")


@dataclass(frozen=True)
class TaggedRelease:
    tag: str
    metadata: ReleaseMetadata


def _git(root: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        stderr = completed.stderr.strip()
        raise ValueError(f"git {' '.join(args)} failed: {stderr or completed.returncode}")
    return completed.stdout


def load_release_history(root: Path) -> list[TaggedRelease]:
    tags = _git(root, "tag", "--list", "v*").splitlines()
    releases: list[TaggedRelease] = []
    for tag in sorted(tags):
        match = RELEASE_TAG_RE.fullmatch(tag)
        if match is None:
            continue
        version = match.group("version")
        if VERSION_NAME_RE.fullmatch(version) is None:
            continue

        build_file = _git(root, "show", f"{tag}:app/build.gradle.kts")
        metadata = parse_release_metadata(build_file)
        if metadata.version_name != version:
            raise ValueError(
                f"{tag} points to versionName {metadata.version_name}, expected {version}"
            )
        releases.append(TaggedRelease(tag=tag, metadata=metadata))
    return releases


def verify_monotonic_version_code(
    current: ReleaseMetadata,
    history: list[TaggedRelease],
) -> None:
    if not history:
        return

    duplicate_name = next(
        (release.tag for release in history if release.metadata.version_name == current.version_name),
        None,
    )
    if duplicate_name is not None:
        raise ValueError(
            f"versionName {current.version_name} already exists at {duplicate_name}"
        )

    highest = max(history, key=lambda release: release.metadata.version_code)
    if current.version_code <= highest.metadata.version_code:
        raise ValueError(
            f"versionCode {current.version_code} must be greater than prior maximum "
            f"{highest.metadata.version_code} from {highest.tag}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    parser.add_argument(
        "--build-file",
        type=Path,
        default=Path("app/build.gradle.kts"),
    )
    args = parser.parse_args()

    root = args.repository_root.resolve()
    build_path = args.build_file
    if not build_path.is_absolute():
        build_path = root / build_path

    try:
        current = parse_release_metadata(build_path.read_text(encoding="utf-8"))
        history = load_release_history(root)
        verify_monotonic_version_code(current, history)
    except (OSError, ValueError) as error:
        raise SystemExit(f"Release version history verification failed: {error}") from error

    maximum = max(
        (release.metadata.version_code for release in history),
        default=0,
    )
    print(
        "Release version history: OK "
        f"(current code={current.version_code}, prior max={maximum})"
    )


if __name__ == "__main__":
    main()
