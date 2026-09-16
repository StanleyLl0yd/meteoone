#!/usr/bin/env python3
"""Verify repository policy excludes release signing secrets and PEPK exports."""

from __future__ import annotations

import fnmatch
import subprocess
from pathlib import Path, PurePosixPath

GITIGNORE = Path(".gitignore")

REQUIRED_IGNORE_PATTERNS = frozenset(
    {
        "*.jks",
        "*.keystore",
        "*.pem",
        "*.key",
        "*.p12",
        "*.pfx",
        "key.properties",
        "keystore.properties",
        "secrets.properties",
        ".env",
        ".env.*",
        ".secrets/",
        "service-account*.json",
        "pepk.jar",
        "*pepk*.zip",
    }
)

FORBIDDEN_TRACKED_PATTERNS = (
    "*.jks",
    "*.keystore",
    "*.pem",
    "*.key",
    "*.p12",
    "*.pfx",
    "key.properties",
    "keystore.properties",
    "secrets.properties",
    ".env",
    ".env.*",
    "service-account*.json",
    "pepk.jar",
    "*pepk*.zip",
)


def parse_gitignore_patterns(text: str) -> set[str]:
    return {
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def missing_required_patterns(text: str) -> set[str]:
    return REQUIRED_IGNORE_PATTERNS - parse_gitignore_patterns(text)


def is_forbidden_tracked_path(path: str) -> bool:
    normalized_path = PurePosixPath(path)
    lowered = normalized_path.as_posix().casefold()
    basename = normalized_path.name.casefold()

    if ".secrets" in (part.casefold() for part in normalized_path.parts):
        return True

    return any(
        fnmatch.fnmatchcase(basename, pattern.casefold())
        for pattern in FORBIDDEN_TRACKED_PATTERNS
    ) or any(
        fnmatch.fnmatchcase(lowered, pattern.casefold())
        for pattern in FORBIDDEN_TRACKED_PATTERNS
        if "/" in pattern
    )


def tracked_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        check=True,
        capture_output=True,
    )
    return [
        item.decode("utf-8", errors="strict")
        for item in result.stdout.split(b"\0")
        if item
    ]


def verify_policy(gitignore_text: str, tracked: list[str]) -> None:
    missing = sorted(missing_required_patterns(gitignore_text))
    if missing:
        raise ValueError(
            "required release-secret ignore patterns are missing: " + ", ".join(missing)
        )

    forbidden = sorted(path for path in tracked if is_forbidden_tracked_path(path))
    if forbidden:
        raise ValueError(
            "release signing/secret artifacts must not be tracked: " + ", ".join(forbidden)
        )


def main() -> None:
    try:
        verify_policy(GITIGNORE.read_text(encoding="utf-8"), tracked_files())
    except (OSError, subprocess.CalledProcessError, UnicodeDecodeError, ValueError) as error:
        raise SystemExit(f"Release secret policy verification failed: {error}") from error

    print("Release secret policy: OK")


if __name__ == "__main__":
    main()
