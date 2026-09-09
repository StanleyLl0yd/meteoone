#!/usr/bin/env python3
"""Fail CI when workflow supply-chain controls regress."""

from __future__ import annotations

import re
from pathlib import Path

ROOTS = (Path(".github/workflows"), Path(".github/actions"))
ACTION_REF = re.compile(r"^(?P<indent>\s*)uses:\s*([^\s#]+)")
IMAGE = re.compile(r"^\s*image:\s*([^\s#]+)")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")
TOP_LEVEL_PERMISSIONS = re.compile(r"(?m)^permissions:\s*(?:\{\}|$)")

errors: list[str] = []

for root in ROOTS:
    if not root.exists():
        continue
    for path in sorted(root.rglob("*")):
        if path.suffix not in {".yml", ".yaml"}:
            continue
        text = path.read_text(encoding="utf-8")
        if path.parent == Path(".github/workflows") and not TOP_LEVEL_PERMISSIONS.search(text):
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
                target = action.group(2)
                if target.startswith("./") or target.startswith("docker://"):
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
                        if following.strip() and len(following) - len(following.lstrip()) <= indent:
                            break
                        block.append(following)
                    if not any(
                        re.match(r"^\s*persist-credentials:\s*false\s*(?:#.*)?$", value)
                        for value in block
                    ):
                        errors.append(
                            f"{path}:{number}: actions/checkout must set persist-credentials: false"
                        )
            image = IMAGE.match(line)
            if image:
                target = image.group(1)
                if target.startswith("${{"):
                    continue
                if not DIGEST.search(target):
                    errors.append(
                        f"{path}:{number}: container image must be pinned by sha256 digest"
                    )

if errors:
    raise SystemExit("\n".join(errors))

print("GitHub Actions supply-chain policy: OK")
