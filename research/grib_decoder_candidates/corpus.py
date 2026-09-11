from __future__ import annotations

import bz2
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path

MANIFEST_PREFIX = "research-output/grib-capability/"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_PROVIDERS = {
    "NOAA_NOMADS": {
        "grid_definition_templates": [0],
        "product_definition_templates": [0, 8],
        "data_representation_templates": [0],
    },
    "ECMWF_OPEN_DATA": {
        "grid_definition_templates": [0],
        "product_definition_templates": [0, 8],
        "data_representation_templates": [42],
    },
    "DWD_OPEN_DATA": {
        "grid_definition_templates": [101],
        "product_definition_templates": [0, 8],
        "data_representation_templates": [42],
    },
}
REPRESENTATIVE_SAMPLES = {
    "noaa_temperature": "samples/noaa/temperature_2m.grib2",
    "noaa_precipitation": "samples/noaa/total_precipitation.grib2",
    "ecmwf_temperature": "samples/ecmwf/temperature_2m.grib2",
    "ecmwf_precipitation": "samples/ecmwf/total_precipitation.grib2",
    "dwd_temperature": "samples/dwd/temperature_2m.grib2.bz2",
    "dwd_precipitation": "samples/dwd/total_precipitation.grib2.bz2",
}


@dataclass(frozen=True)
class CorpusVerification:
    file_count: int
    sample_count: int
    index_sha256: str


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_pins() -> dict[str, object]:
    path = Path(__file__).with_name("pins.json")
    return json.loads(path.read_text(encoding="utf-8"))


def _parse_manifest(root: Path) -> dict[str, str]:
    manifest = root / "SHA256SUMS"
    entries: dict[str, str] = {}
    for line_number, raw_line in enumerate(
        manifest.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if not raw_line:
            continue
        try:
            digest, source_path = raw_line.split("  ", 1)
        except ValueError as error:
            raise ValueError(f"Malformed SHA256SUMS line {line_number}") from error
        if SHA256.fullmatch(digest) is None:
            raise ValueError(f"Invalid SHA-256 on line {line_number}")
        if not source_path.startswith(MANIFEST_PREFIX):
            raise ValueError(f"Unexpected artifact path on line {line_number}")
        relative = source_path.removeprefix(MANIFEST_PREFIX)
        candidate = Path(relative)
        if candidate.is_absolute() or ".." in candidate.parts or not candidate.parts:
            raise ValueError(f"Unsafe artifact path on line {line_number}")
        if relative in entries:
            raise ValueError(f"Duplicate artifact path on line {line_number}")
        entries[relative] = digest
    if not entries:
        raise ValueError("SHA256SUMS is empty")
    return entries


def verify_corpus(root: Path) -> CorpusVerification:
    root = root.resolve(strict=True)
    pins = _load_pins()["corpus"]
    entries = _parse_manifest(root)

    actual_files = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file() and path.name != "SHA256SUMS"
    }
    if actual_files != set(entries):
        missing = sorted(set(entries) - actual_files)
        extra = sorted(actual_files - set(entries))
        raise ValueError(f"Artifact file set mismatch: missing={missing}, extra={extra}")

    for relative, expected_digest in entries.items():
        path = root / relative
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Artifact entry is not a regular file: {relative}")
        actual_digest = _sha256(path)
        if actual_digest != expected_digest:
            raise ValueError(f"Artifact checksum mismatch: {relative}")

    evidence = json.loads((root / "evidence.json").read_text(encoding="utf-8"))
    if evidence.get("schema_version") != 3 or evidence.get("success") is not True:
        raise ValueError("Capability evidence is not a successful schema-v3 probe")
    if evidence.get("errors") != []:
        raise ValueError("Capability evidence contains provider errors")
    if evidence.get("model_run") != pins["model_run"]:
        raise ValueError("Capability evidence model run drifted")
    if evidence.get("forecast_hour") != pins["forecast_hour"]:
        raise ValueError("Capability evidence forecast hour drifted")
    if evidence.get("sample_count") != 28 or len(evidence.get("samples", [])) != 28:
        raise ValueError("Capability evidence sample count drifted")
    if evidence.get("providers") != EXPECTED_PROVIDERS:
        raise ValueError("Measured decoder template envelope drifted")

    index_path = root / "samples/ecmwf/index.jsonl"
    index_sha256 = _sha256(index_path)
    if index_sha256 != pins["ecmwf_index_sha256"]:
        raise ValueError("Retained ECMWF index checksum drifted")

    for relative in REPRESENTATIVE_SAMPLES.values():
        if relative not in entries:
            raise ValueError(f"Representative sample missing from manifest: {relative}")

    return CorpusVerification(
        file_count=len(entries),
        sample_count=evidence["sample_count"],
        index_sha256=index_sha256,
    )


def prepare_representative_samples(root: Path, output: Path) -> dict[str, Path]:
    from research.grib_capabilities.grib2 import inspect_grib2

    verify_corpus(root)
    output.mkdir(parents=True, exist_ok=True)
    prepared: dict[str, Path] = {}
    for name, relative in REPRESENTATIVE_SAMPLES.items():
        source = root / relative
        target = output / f"{name}.grib2"
        if source.suffix == ".bz2":
            target.write_bytes(bz2.decompress(source.read_bytes()))
        else:
            target.write_bytes(source.read_bytes())
        messages = inspect_grib2(target.read_bytes())
        if not messages:
            raise ValueError(f"Prepared sample contains no GRIB2 messages: {name}")
        prepared[name] = target
    return prepared
