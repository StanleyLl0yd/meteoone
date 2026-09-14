#!/usr/bin/env python3
"""Fail CI when workflow supply-chain controls regress."""

from __future__ import annotations

import re
from pathlib import Path

ROOTS = (Path(".github/workflows"), Path(".github/actions"))
ACTION_REF = re.compile(r"^(?P<indent>\s*)(?:-\s+)?uses:\s*([^\s#]+)")
IMAGE = re.compile(r"^\s*image:\s*(?P<target>.+?)\s*(?:#.*)?$")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")
TOP_LEVEL_PERMISSIONS = re.compile(r"(?m)^permissions:\s*(?:\{\}|$)")


def _unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def verify_document(path: Path, text: str, *, is_workflow: bool) -> list[str]:
    errors: list[str] = []
    if is_workflow and not TOP_LEVEL_PERMISSIONS.search(text):
        errors.append(f"{path}: explicit top-level workflow permissions are required")
    if "pull_request_target:" in text:
        errors.append(f"{path}: pull_request_target is forbidden")
    if "persist-credentials: true" in text:
        errors.append(f"{path}: checkout credentials must not persist")
    if "secrets: inherit" in text:
        errors.append(f"{path}: reusable-workflow secret inheritance is forbidden")

    lines = text.splitlines()
    for number, line in enumerate(lines, start=1):
        action = ACTION_REF.match(line)
        if action:
            target = _unquote(action.group(2))
            if target.startswith("./"):
                continue
            if target.startswith("docker://"):
                image = target.removeprefix("docker://")
                if not DIGEST.search(image):
                    errors.append(
                        f"{path}:{number}: Docker action image must be pinned by sha256 digest"
                    )
                continue
            if "@" not in target:
                errors.append(f"{path}:{number}: action is not pinned")
                continue
            owner_action, ref = target.rsplit("@", 1)
            if not FULL_SHA.fullmatch(ref):
                errors.append(
                    f"{path}:{number}: {owner_action} must use a full 40-character SHA"
                )
            if owner_action == "actions/checkout":
                indent = len(action.group("indent"))
                block = []
                for following in lines[number:]:
                    following_indent = len(following) - len(following.lstrip())
                    if following.lstrip().startswith("- ") and following_indent < indent:
                        break
                    block.append(following)
                if not any(
                    re.match(r"^\s*persist-credentials:\s*false\s*(?:#.*)?$", value)
                    for value in block
                ):
                    errors.append(
                        f"{path}:{number}: actions/checkout must set persist-credentials: false"
                    )

        image_match = IMAGE.match(line)
        if image_match:
            target = _unquote(image_match.group("target").strip())
            if target.startswith("${{"):
                errors.append(
                    f"{path}:{number}: dynamic container image cannot be verified; "
                    "use a static sha256 digest"
                )
            elif not DIGEST.search(target):
                errors.append(
                    f"{path}:{number}: container image must be pinned by sha256 digest"
                )

    return errors


def collect_errors(root: Path = Path(".")) -> list[str]:
    errors: list[str] = []
    for relative_root in ROOTS:
        directory = root / relative_root
        if not directory.exists():
            continue
        for path in sorted(directory.rglob("*")):
            if path.suffix not in {".yml", ".yaml"}:
                continue
            relative_path = path.relative_to(root)
            errors.extend(
                verify_document(
                    relative_path,
                    path.read_text(encoding="utf-8"),
                    is_workflow=relative_path.parent == Path(".github/workflows"),
                )
            )
    return errors


def main() -> None:
    errors = collect_errors()
    if errors:
        raise SystemExit("\n".join(errors))
    print("GitHub Actions supply-chain policy: OK")


if __name__ == "__main__":
    main()
