from __future__ import annotations

import hashlib
import struct
import tempfile
import unittest
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

from scripts.verify_release_apk_native import PAGE_SIZE, REQUIRED_NATIVE, verify_apk_native


def elf64(*, machine: int = 183, align: int = PAGE_SIZE) -> bytes:
    payload = bytearray(128)
    payload[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", payload, 16, 3)
    struct.pack_into("<H", payload, 18, machine)
    struct.pack_into("<Q", payload, 32, 64)
    struct.pack_into("<HH", payload, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", payload, 64, 1, 5, 0, 0, 0, 64, 64, align)
    return bytes(payload)


def elf32(*, machine: int = 3, align: int = PAGE_SIZE) -> bytes:
    payload = bytearray(96)
    payload[:6] = b"\x7fELF\x01\x01"
    struct.pack_into("<H", payload, 16, 3)
    struct.pack_into("<H", payload, 18, machine)
    struct.pack_into("<I", payload, 28, 52)
    struct.pack_into("<HH", payload, 42, 32, 1)
    struct.pack_into("<IIIIIIII", payload, 52, 1, 0, 0, 0, 52, 52, 5, align)
    return bytes(payload)


def required_libraries(*, override: dict[str, bytes] | None = None) -> dict[str, bytes]:
    libs = {f"lib/arm64-v8a/{name}": elf64() for name in REQUIRED_NATIVE}
    libs.update(override or {})
    return libs


def manifest_for(libs: dict[str, bytes]) -> dict:
    return {
        "files": {
            f"jniLibs/arm64-v8a/{name}": {
                "bytes": len(libs[f"lib/arm64-v8a/{name}"]),
                "sha256": hashlib.sha256(libs[f"lib/arm64-v8a/{name}"]).hexdigest(),
            }
            for name in REQUIRED_NATIVE
        }
    }


def make_apk(
    path: Path, libs: dict[str, bytes], *, aligned: bool = True, compressed: bool = False
) -> None:
    with ZipFile(path, "w") as archive:
        for name, payload in sorted(libs.items()):
            info = ZipInfo(name)
            info.compress_type = ZIP_DEFLATED if compressed else ZIP_STORED
            if aligned:
                # Valid ZIP extra-field alignment, calculated from this entry's local header.
                next_payload = archive.fp.tell() + 30 + len(name.encode("utf-8")) + 4
                padding = -next_payload % PAGE_SIZE
                info.extra = struct.pack("<HH", 0xCAFE, padding) + bytes(padding)
            archive.writestr(info, payload)


class ReleaseApkNativeVerificationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name) / "test.apk"

    def test_valid_vendor_libs_and_other_abi_libraries(self) -> None:
        libs = required_libraries()
        libs["lib/x86/libexample.so"] = elf32()
        libs["lib/armeabi-v7a/libexample.so"] = elf32(machine=40)
        libs["lib/x86_64/libexample.so"] = elf64(machine=62)
        make_apk(self.apk, libs)

        self.assertEqual(len(libs), verify_apk_native(self.apk, manifest_for(libs)))

    def test_missing_required_library_is_rejected(self) -> None:
        libs = required_libraries()
        manifest = manifest_for(libs)
        libs.pop("lib/arm64-v8a/libeccodes.so")
        make_apk(self.apk, libs)
        with self.assertRaisesRegex(ValueError, "APK missing required"):
            verify_apk_native(self.apk, manifest)

    def test_manifest_digest_drift_is_rejected(self) -> None:
        libs = required_libraries()
        manifest = manifest_for(libs)
        manifest["files"]["jniLibs/arm64-v8a/libaec.so"]["sha256"] = "0" * 64
        make_apk(self.apk, libs)
        with self.assertRaisesRegex(ValueError, "digest drift"):
            verify_apk_native(self.apk, manifest)

    def test_unaligned_payload_is_rejected(self) -> None:
        libs = required_libraries()
        make_apk(self.apk, libs, aligned=False)
        with self.assertRaisesRegex(ValueError, "not aligned"):
            verify_apk_native(self.apk, manifest_for(libs))

    def test_compressed_native_library_is_rejected(self) -> None:
        libs = required_libraries()
        make_apk(self.apk, libs, compressed=True)
        with self.assertRaisesRegex(ValueError, "compressed ELF"):
            verify_apk_native(self.apk, manifest_for(libs))

    def test_elf_segment_with_4k_alignment_is_rejected(self) -> None:
        libs = required_libraries(
            override={"lib/arm64-v8a/libmeteoone_grib_jni.so": elf64(align=4096)}
        )
        make_apk(self.apk, libs)
        with self.assertRaisesRegex(ValueError, "incompatible 16 KiB"):
            verify_apk_native(self.apk, manifest_for(libs))

    def test_wrong_elf_machine_is_rejected(self) -> None:
        libs = required_libraries(override={"lib/arm64-v8a/libaec.so": elf64(machine=62)})
        make_apk(self.apk, libs)
        with self.assertRaisesRegex(ValueError, "unexpected ELF machine"):
            verify_apk_native(self.apk, manifest_for(libs))


if __name__ == "__main__":
    unittest.main()
