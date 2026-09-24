#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${OUTPUT_DIR:?OUTPUT_DIR is required}"
: "${JAVA_HOME:?JAVA_HOME is required}"

for command in git cmake cc python3 readelf sha256sum; do
  command -v "$command" >/dev/null
done
test -f "$JAVA_HOME/include/jni.h"
test -f "$JAVA_HOME/include/linux/jni_md.h"

readarray -t pins < <(python3 - <<'PY'
import json
from pathlib import Path
config = json.loads(Path("research/grib_decoder_candidates/pins.json").read_text())
for name in ("eccodes", "libaec"):
    print(config["candidates"][name]["repository"])
    print(config["candidates"][name]["commit"])
print(config["tooling"]["ecbuild"]["repository"])
print(config["tooling"]["ecbuild"]["commit"])
PY
)
ECCODES_REPOSITORY="${pins[0]}"
ECCODES_COMMIT="${pins[1]}"
LIBAEC_REPOSITORY="${pins[2]}"
LIBAEC_COMMIT="${pins[3]}"
ECBUILD_REPOSITORY="${pins[4]}"
ECBUILD_COMMIT="${pins[5]}"

work_root="$RUNNER_TEMP/meteoone-server-grib-native"
src_root="$work_root/src"
build_root="$work_root/build"
prefix_root="$work_root/prefix"
aec_prefix="$prefix_root/libaec"
eccodes_prefix="$prefix_root/eccodes"
bundle_root="$OUTPUT_DIR/bundle"
evidence_root="$OUTPUT_DIR/evidence"

rm -rf "$work_root" "$OUTPUT_DIR"
mkdir -p "$src_root" "$build_root" "$prefix_root"   "$bundle_root/lib" "$bundle_root/definitions" "$bundle_root/licenses"   "$evidence_root"

fetch_exact() {
  local repository="$1"
  local commit="$2"
  local destination="$3"
  git init -q "$destination"
  git -C "$destination" remote add origin "$repository"
  git -C "$destination" -c protocol.version=2 fetch --quiet --no-tags --depth=1 origin "$commit"
  git -C "$destination" checkout --quiet --detach FETCH_HEAD
  test "$(git -C "$destination" rev-parse HEAD)" = "$commit"
}

fetch_exact "$ECCODES_REPOSITORY" "$ECCODES_COMMIT" "$src_root/eccodes"
fetch_exact "$LIBAEC_REPOSITORY" "$LIBAEC_COMMIT" "$src_root/libaec"
fetch_exact "$ECBUILD_REPOSITORY" "$ECBUILD_COMMIT" "$src_root/ecbuild"

{
  printf 'eccodes\t%s\n' "$(git -C "$src_root/eccodes" rev-parse HEAD)"
  printf 'libaec\t%s\n' "$(git -C "$src_root/libaec" rev-parse HEAD)"
  printf 'ecbuild\t%s\n' "$(git -C "$src_root/ecbuild" rev-parse HEAD)"
  cmake --version | head -n 1
  cc --version | head -n 1
  java -version 2>&1 | head -n 1
} > "$evidence_root/toolchain.tsv"

cmake -S "$src_root/libaec" -B "$build_root/libaec"   -DCMAKE_BUILD_TYPE=Release   -DCMAKE_INSTALL_PREFIX="$aec_prefix"   -DCMAKE_INSTALL_LIBDIR=lib   -DCMAKE_INSTALL_RPATH="\$ORIGIN"   -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON   -DBUILD_SHARED_LIBS=ON   -DBUILD_STATIC_LIBS=OFF   -DBUILD_TESTING=OFF   2>&1 | tee "$evidence_root/libaec-configure.log"
cmake --build "$build_root/libaec" --target install --parallel 2   2>&1 | tee "$evidence_root/libaec-build.log"

cmake -S "$src_root/eccodes" -B "$build_root/eccodes"   -DCMAKE_BUILD_TYPE=Release   -DCMAKE_INSTALL_PREFIX="$eccodes_prefix"   -DCMAKE_INSTALL_LIBDIR=lib   -DCMAKE_INSTALL_RPATH="\$ORIGIN"   -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON   -DCMAKE_MODULE_PATH="$src_root/ecbuild/cmake"   -DCMAKE_PREFIX_PATH="$aec_prefix"   -DBUILD_SHARED_LIBS=ON   -DENABLE_PRODUCT_GRIB=ON   -DENABLE_PRODUCT_BUFR=OFF   -DENABLE_AEC=ON   -DENABLE_USE_SHARED_LIB_AEC=ON   -DENABLE_ECCODES_THREADS=OFF   -DENABLE_EXAMPLES=OFF   -DENABLE_BUILD_TOOLS=OFF   -DENABLE_TESTS=OFF   -DENABLE_GEOGRAPHY=OFF   -DENABLE_JPG=OFF   -DENABLE_PNG=OFF   -DENABLE_NETCDF=OFF   -DENABLE_FORTRAN=OFF   -DENABLE_PYTHON=OFF   -DENABLE_MEMFS=OFF   -DENABLE_INSTALL_ECCODES_DEFINITIONS=ON   -DENABLE_INSTALL_ECCODES_SAMPLES=OFF   2>&1 | tee "$evidence_root/eccodes-configure.log"
cmake --build "$build_root/eccodes" --target install --parallel 2   2>&1 | tee "$evidence_root/eccodes-build.log"

for library in libaec.so libsz.so; do
  test -e "$aec_prefix/lib/$library"
  cp -L "$aec_prefix/lib/$library" "$bundle_root/lib/$library"
done
test -e "$eccodes_prefix/lib/libeccodes.so"
cp -L "$eccodes_prefix/lib/libeccodes.so" "$bundle_root/lib/libeccodes.so"

cc   -std=c11   -O2   -fPIC   -Wall   -Wextra   -Werror   -I"$JAVA_HOME/include"   -I"$JAVA_HOME/include/linux"   -I"$eccodes_prefix/include"   -shared   -Wl,--no-undefined   -Wl,-rpath,'$ORIGIN'   -Wl,-soname,libmeteoone_grib_jni.so   forecast/data/native/meteoone_grib_jni.c   -L"$eccodes_prefix/lib"   -L"$aec_prefix/lib"   -leccodes   -lm   -o "$bundle_root/lib/libmeteoone_grib_jni.so"   2>&1 | tee "$evidence_root/jni-bridge-build.log"

definitions_dir="$(find "$eccodes_prefix" -type d -path '*/eccodes/definitions' -print -quit)"
test -n "$definitions_dir"
cp -RL "$definitions_dir/." "$bundle_root/definitions/"

cp "$src_root/eccodes/LICENSE" "$bundle_root/licenses/eccodes-LICENSE"
cp "$src_root/eccodes/NOTICE" "$bundle_root/licenses/eccodes-NOTICE"
cp "$src_root/libaec/LICENSE.txt" "$bundle_root/licenses/libaec-LICENSE.txt"

python3 - "$bundle_root" "$ECCODES_COMMIT" "$LIBAEC_COMMIT" "$ECBUILD_COMMIT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
definitions = root / "definitions"

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

all_definition_entries = list(definitions.rglob("*"))
if any(p.is_symlink() for p in all_definition_entries):
    raise SystemExit("ecCodes definitions tree contains a symlink")
definition_files = sorted(p for p in all_definition_entries if p.is_file())
if not definition_files:
    raise SystemExit("ecCodes definitions tree is empty")

definitions_manifest = root / "definitions.sha256"
with definitions_manifest.open("w", encoding="utf-8", newline="\n") as out:
    for path in definition_files:
        relative = path.relative_to(definitions).as_posix()
        out.write(f"{digest(path)}  {relative}\n")

runtime_files = [
    Path("definitions.sha256"),
    Path("lib/libaec.so"),
    Path("lib/libeccodes.so"),
    Path("lib/libmeteoone_grib_jni.so"),
    Path("lib/libsz.so"),
    Path("licenses/eccodes-LICENSE"),
    Path("licenses/eccodes-NOTICE"),
    Path("licenses/libaec-LICENSE.txt"),
]
files = {}
for relative in runtime_files:
    path = root / relative
    payload_size = path.stat().st_size
    if payload_size <= 0:
        raise SystemExit(f"empty server runtime file: {relative}")
    files[relative.as_posix()] = {
        "bytes": payload_size,
        "sha256": digest(path),
    }

manifest = {
    "schema_version": 1,
    "platform": "linux-x86_64",
    "eccodes_commit": sys.argv[2],
    "libaec_commit": sys.argv[3],
    "ecbuild_commit": sys.argv[4],
    "threads": False,
    "definition_file_count": len(definition_files),
    "files": files,
}
(root / "manifest.json").write_text(
    json.dumps(manifest, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY

: > "$evidence_root/native-elf.txt"
for library in "$bundle_root"/lib/*.so; do
  {
    printf '\n===== %s =====\n' "$(basename "$library")"
    readelf -h "$library"
    readelf -d "$library"
  } >> "$evidence_root/native-elf.txt"
  readelf -h "$library" | grep -Eq 'Machine:[[:space:]]+Advanced Micro Devices X86-64'
done

python3 - "$bundle_root/lib" <<'PY'
import re
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
packaged = {p.name for p in root.glob("*.so")}
system = {
    "libc.so.6",
    "libdl.so.2",
    "libgcc_s.so.1",
    "libm.so.6",
    "libpthread.so.0",
    "librt.so.1",
    "libstdc++.so.6",
    "ld-linux-x86-64.so.2",
}
for library in sorted(root.glob("*.so")):
    output = subprocess.check_output(["readelf", "-d", str(library)], text=True)
    needed = set(re.findall(r"Shared library: \[([^]]+)\]", output))
    missing = needed - packaged - system
    if missing:
        raise SystemExit(
            f"{library.name} has unstaged DT_NEEDED entries: {sorted(missing)}"
        )
    if needed & packaged and "$ORIGIN" not in output:
        raise SystemExit(
            f"{library.name} depends on bundled libraries without $ORIGIN RUNPATH"
        )
PY

find "$bundle_root" -type f -print0 | sort -z | xargs -0 sha256sum   > "$evidence_root/bundle-sha256.txt"
find "$bundle_root" -type f -printf '%P\t%s\n' | sort   > "$evidence_root/bundle-files.tsv"
