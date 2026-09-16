#!/usr/bin/env python3
"""Export the public signing certificate from a signed Android App Bundle.

The certificate is public material. This helper never reads a keystore or private key.
It expects exactly one JAR-signature block in META-INF, matching MeteoOne's
single-signer release policy.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import re
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

SIGNATURE_BLOCK_RE = re.compile(r"^META-INF/[^/]+\.(?:RSA|DSA|EC)$", re.IGNORECASE)
PEM_RE = re.compile(
    rb"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----",
    re.DOTALL,
)


def normalize_fingerprint(value: str) -> str:
    normalized = re.sub(r"[^0-9A-Fa-f]", "", value).lower()
    if len(normalized) != 64:
        raise ValueError("expected a SHA-256 fingerprint containing exactly 64 hex digits")
    return normalized


def export_certificate(aab_path: Path, output_path: Path, expected_sha256: str | None) -> str:
    if not aab_path.is_file():
        raise ValueError(f"AAB does not exist: {aab_path}")

    with ZipFile(aab_path) as archive:
        signature_blocks = [name for name in archive.namelist() if SIGNATURE_BLOCK_RE.match(name)]
        if len(signature_blocks) != 1:
            raise ValueError(
                f"expected exactly one JAR signature block, found {len(signature_blocks)}: "
                + ", ".join(signature_blocks)
            )
        signature_bytes = archive.read(signature_blocks[0])

    with tempfile.NamedTemporaryFile() as signature_file:
        signature_file.write(signature_bytes)
        signature_file.flush()
        completed = subprocess.run(
            ["keytool", "-printcert", "-rfc", "-file", signature_file.name],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    matches = PEM_RE.findall(completed.stdout)
    if len(matches) != 1:
        raise ValueError(f"expected exactly one certificate from keytool, found {len(matches)}")

    der = base64.b64decode(re.sub(rb"\s+", b"", matches[0]), validate=True)
    fingerprint = hashlib.sha256(der).hexdigest()

    if expected_sha256 is not None:
        expected = normalize_fingerprint(expected_sha256)
        if fingerprint != expected:
            raise ValueError(
                f"certificate SHA-256 mismatch: expected {expected}, got {fingerprint}"
            )

    pem = (
        b"-----BEGIN CERTIFICATE-----\n"
        + b"\n".join(
            base64.b64encode(der)[offset : offset + 64]
            for offset in range(0, len(base64.b64encode(der)), 64)
        )
        + b"\n-----END CERTIFICATE-----\n"
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(pem)
    return fingerprint


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export the single public signing certificate from a signed AAB as PEM."
    )
    parser.add_argument("aab", type=Path, help="signed Android App Bundle")
    parser.add_argument("output", type=Path, help="destination PEM file")
    parser.add_argument(
        "--expected-sha256",
        help="optional expected SHA-256 certificate fingerprint (colon-separated or plain hex)",
    )
    args = parser.parse_args()

    try:
        fingerprint = export_certificate(args.aab, args.output, args.expected_sha256)
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        parser.error(str(exc))

    print(f"certificate_sha256={fingerprint}")
    print(f"output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
