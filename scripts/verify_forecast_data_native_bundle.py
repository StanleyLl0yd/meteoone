#!/usr/bin/env python3
"""Verify the vendored GRIB native runtime bundle and its Android AAR packaging."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
DATA_ROOT = ROOT / "forecast" / "data"
MANIFEST_PATH = DATA_ROOT / "native" / "manifest.json"
PROVENANCE_PATH = DATA_ROOT / "native" / "generated-provenance.json"

EXPECTED_MANIFEST_SHA256 = "1225e5f40ba63db456160f73114c5dd4fbfdeea39629db9dd993d304ea57fb5f"
EXPECTED_PROVENANCE_SHA256 = "1a431dab2a5d08979cafadf6516e17677df22ea34b0c54e3a32c6a56228f858e"
EXPECTED_PROVENANCE = {
    "run_attempt": 1,
    "run_id": 34834603450,
    "schema_version": 1,
    "source_sha": "2f675e3541ccaff08fd7ff998133d0fb46e769a7",
    "workflow": "GRIB Native Bundle",
}
EXPECTED_MANIFEST_METADATA = {
    "schema_version": 1,
    "eccodes_commit": "19e71ddcc8f45862a3909b4dae690c3cc95bbc16",
    "libaec_commit": "7204505af7d6635734fc12a38d6bd0a6253c9c6d",
    "ecbuild_commit": "60e7d659ec10a4316e0ec27be28254092dcd7921",
    "android_api": 26,
    "android_abi": "arm64-v8a",
    "memfs": False,
    "flexible_page_sizes": True,
}
EXPECTED_BUNDLE_FILES = {
    "assets/eccodes-definitions.zip",
    "jniLibs/arm64-v8a/libaec.so",
    "jniLibs/arm64-v8a/libeccodes.so",
    "jniLibs/arm64-v8a/libmeteoone_grib_jni.so",
    "jniLibs/arm64-v8a/libsz.so",
    "licenses/eccodes-LICENSE",
    "licenses/eccodes-NOTICE",
    "licenses/libaec-LICENSE.txt",
}


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def load_pinned_json(path: Path, expected_sha256: str) -> dict[str, object]:
    payload = path.read_bytes()
    actual = sha256(payload)
    if actual != expected_sha256:
        raise SystemExit(f"{path.relative_to(ROOT)} sha256 drifted: {actual}")
    return json.loads(payload)


def source_path(bundle_path: str) -> Path:
    if bundle_path.startswith("assets/"):
        return DATA_ROOT / "src" / "main" / bundle_path
    if bundle_path.startswith("jniLibs/"):
        return DATA_ROOT / "src" / "main" / bundle_path
    if bundle_path.startswith("licenses/"):
        return (
            DATA_ROOT
            / "src"
            / "main"
            / "assets"
            / "third_party_licenses"
            / bundle_path.removeprefix("licenses/")
        )
    raise SystemExit(f"unsupported manifest path: {bundle_path}")


def aar_path(bundle_path: str) -> str:
    if bundle_path.startswith("assets/"):
        return bundle_path
    if bundle_path.startswith("jniLibs/"):
        return "jni/" + bundle_path.removeprefix("jniLibs/")
    if bundle_path.startswith("licenses/"):
        return "assets/third_party_licenses/" + bundle_path.removeprefix("licenses/")
    raise SystemExit(f"unsupported manifest path: {bundle_path}")


def verify_payload(label: str, payload: bytes, metadata: object) -> None:
    if not isinstance(metadata, dict):
        raise SystemExit(f"{label} manifest metadata is not an object")
    expected_bytes = metadata.get("bytes")
    expected_sha256 = metadata.get("sha256")
    if len(payload) != expected_bytes:
        raise SystemExit(f"{label} size drifted: {len(payload)} != {expected_bytes}")
    actual_sha256 = sha256(payload)
    if actual_sha256 != expected_sha256:
        raise SystemExit(f"{label} sha256 drifted: {actual_sha256} != {expected_sha256}")


def verify_source() -> dict[str, object]:
    manifest = load_pinned_json(MANIFEST_PATH, EXPECTED_MANIFEST_SHA256)
    provenance = load_pinned_json(PROVENANCE_PATH, EXPECTED_PROVENANCE_SHA256)

    if provenance != EXPECTED_PROVENANCE:
        raise SystemExit(f"generated provenance drifted: {provenance!r}")

    for key, expected in EXPECTED_MANIFEST_METADATA.items():
        actual = manifest.get(key)
        if actual != expected:
            raise SystemExit(f"manifest {key} drifted: {actual!r} != {expected!r}")

    files = manifest.get("files")
    if not isinstance(files, dict):
        raise SystemExit("manifest files is not an object")
    if set(files) != EXPECTED_BUNDLE_FILES:
        raise SystemExit(f"manifest file set drifted: {sorted(files)}")

    for bundle_path, metadata in files.items():
        path = source_path(bundle_path)
        verify_payload(str(path.relative_to(ROOT)), path.read_bytes(), metadata)

    return manifest


def verify_aar(path: Path, manifest: dict[str, object]) -> None:
    files = manifest["files"]
    if not isinstance(files, dict):
        raise SystemExit("manifest files is not an object")

    with ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise SystemExit("AAR contains duplicate ZIP entry names")

        expected_entries = {aar_path(bundle_path) for bundle_path in files}
        for bundle_path, metadata in files.items():
            packaged_path = aar_path(bundle_path)
            try:
                payload = archive.read(packaged_path)
            except KeyError as exc:
                raise SystemExit(f"AAR is missing {packaged_path}") from exc
            verify_payload(f"{path}:{packaged_path}", payload, metadata)

        actual_native_entries = {
            name
            for name in names
            if name.startswith("jni/") and not name.endswith("/")
        }
        expected_native_entries = {
            entry for entry in expected_entries if entry.startswith("jni/")
        }
        if actual_native_entries != expected_native_entries:
            raise SystemExit(
                "AAR native file set drifted: "
                f"{sorted(actual_native_entries)} != {sorted(expected_native_entries)}"
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--aar",
        type=Path,
        help="Also verify the built forecast:data AAR contains the exact vendored payloads.",
    )
    args = parser.parse_args()

    manifest = verify_source()
    if args.aar is not None:
        verify_aar(args.aar, manifest)

    print("forecast data native bundle verification: OK")


if __name__ == "__main__":
    main()
