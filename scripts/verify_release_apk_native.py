#!/usr/bin/env python3
"""Validate native ELF identity, provenance and 16 KiB ZIP alignment in release APKs."""

from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

from scripts.verify_forecast_data_native_bundle import verify_source

PAGE_SIZE = 16 * 1024
ELF_MACHINE = {
    "arm64-v8a": 183,
    "armeabi-v7a": 40,
    "x86": 3,
    "x86_64": 62,
}
REQUIRED_NATIVE = {
    "libaec.so",
    "libeccodes.so",
    "libmeteoone_grib_jni.so",
    "libsz.so",
}


def verify_elf(payload: bytes, abi: str, label: str) -> None:
    if len(payload) < 6 or payload[:4] != b"\x7fELF":
        raise ValueError(f"{label}: invalid ELF magic")
    if abi in ("armeabi-v7a", "x86"):
        verify_elf32(payload, abi, label)
        return
    if len(payload) < 64 or payload[4:6] != b"\x02\x01":
        raise ValueError(f"{label}: expected little-endian ELF64")
    if struct.unpack_from("<H", payload, 18)[0] != ELF_MACHINE[abi]:
        raise ValueError(f"{label}: unexpected ELF machine for {abi}")
    phoff = struct.unpack_from("<Q", payload, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", payload, 54)
    if phentsize < 56 or not 0 < phnum <= 128 or phoff + phentsize * phnum > len(payload):
        raise ValueError(f"{label}: invalid ELF program header table")
    loads = 0
    for i in range(phnum):
        ptype, _, offset, vaddr, _, filesz, memsz, align = struct.unpack_from(
            "<IIQQQQQQ", payload, phoff + i * phentsize
        )
        if ptype != 1:
            continue
        loads += 1
        verify_load(payload, offset, vaddr, filesz, memsz, align, label)
    if not loads:
        raise ValueError(f"{label}: no loadable ELF segments")


def verify_elf32(payload: bytes, abi: str, label: str) -> None:
    if len(payload) < 52 or payload[:6] != b"\x7fELF\x01\x01":
        raise ValueError(f"{label}: expected little-endian ELF32")
    if struct.unpack_from("<H", payload, 18)[0] != ELF_MACHINE[abi]:
        raise ValueError(f"{label}: unexpected ELF machine for {abi}")
    phoff = struct.unpack_from("<I", payload, 28)[0]
    phentsize, phnum = struct.unpack_from("<HH", payload, 42)
    if phentsize < 32 or not 0 < phnum <= 128 or phoff + phentsize * phnum > len(payload):
        raise ValueError(f"{label}: invalid ELF program header table")
    loads = 0
    for i in range(phnum):
        ptype, offset, vaddr, _, filesz, memsz, _, align = struct.unpack_from(
            "<IIIIIIII", payload, phoff + i * phentsize
        )
        if ptype != 1:
            continue
        loads += 1
        verify_load(payload, offset, vaddr, filesz, memsz, align, label)
    if not loads:
        raise ValueError(f"{label}: no loadable ELF segments")


def verify_load(
    payload: bytes, offset: int, vaddr: int, filesz: int, memsz: int, align: int, label: str
) -> None:
    if (
        align < PAGE_SIZE
        or align & (align - 1)
        or offset % PAGE_SIZE != vaddr % PAGE_SIZE
        or filesz > memsz
        or offset + filesz > len(payload)
    ):
        raise ValueError(f"{label}: incompatible 16 KiB ELF PT_LOAD segment")


def local_payload_offset(apk, info) -> int:
    apk.seek(info.header_offset)
    header = apk.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError(f"{info.filename}: invalid local ZIP header")
    local_name_len, local_extra_len = struct.unpack_from("<HH", header, 26)
    local_name = apk.read(local_name_len)
    if local_name != info.filename.encode("utf-8"):
        raise ValueError(f"{info.filename}: local ZIP filename mismatch")
    return info.header_offset + 30 + local_name_len + local_extra_len


def verify_apk_native(path: Path, manifest: dict) -> int:
    metadata = manifest.get("files")
    if not isinstance(metadata, dict):
        raise ValueError("native bundle manifest missing files")
    required = {
        key.removeprefix("jniLibs/arm64-v8a/"): value
        for key, value in metadata.items()
        if key.startswith("jniLibs/arm64-v8a/")
    }
    if set(required) != REQUIRED_NATIVE:
        raise ValueError("unexpected required native library manifest set")

    with ZipFile(path) as archive, path.open("rb") as apk:
        entries = archive.infolist()
        names = [item.filename for item in entries]
        if len(names) != len(set(names)):
            raise ValueError("APK has duplicate ZIP entries")
        native = [item for item in entries if item.filename.startswith("lib/") and item.filename.endswith(".so")]
        if not native:
            raise ValueError("APK contains no native libraries")
        packaged = {item.filename: item for item in native}
        for name in required:
            if f"lib/arm64-v8a/{name}" not in packaged:
                raise ValueError(f"APK missing required lib/arm64-v8a/{name}")

        for info in native:
            parts = info.filename.split("/")
            if len(parts) != 3 or parts[1] not in ELF_MACHINE:
                raise ValueError(f"{info.filename}: unexpected native ABI or path")
            if info.compress_type != ZIP_STORED:
                raise ValueError(f"{info.filename}: compressed ELF library")
            if local_payload_offset(apk, info) % PAGE_SIZE:
                raise ValueError(f"{info.filename}: ZIP payload not aligned to 16 KiB")
            payload = archive.read(info)
            verify_elf(payload, parts[1], info.filename)
            if parts[1] == "arm64-v8a" and parts[2] in required:
                expected = required[parts[2]]
                if (
                    len(payload) != expected.get("bytes")
                    or hashlib.sha256(payload).hexdigest() != expected.get("sha256")
                ):
                    raise ValueError(f"{info.filename}: release native bundle digest drift")
        return len(native)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    try:
        count = verify_apk_native(args.apk, verify_source())
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f"release APK native verification failed: {error}\n")
    print(f"release APK native verification: OK ({count} native libraries, 16 KiB aligned)")


if __name__ == "__main__":
    main()
