#!/usr/bin/env bash
set -euo pipefail

: "${NDK_ROOT:?NDK_ROOT is required}"
: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${OUTPUT_DIR:?OUTPUT_DIR is required}"

ANDROID_API="${ANDROID_API:-26}"
ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
ECBUILD_REPOSITORY="https://github.com/ecmwf/ecbuild.git"
ECBUILD_COMMIT="60e7d659ec10a4316e0ec27be28254092dcd7921"

if [[ "$ANDROID_API" != "26" || "$ANDROID_ABI" != "arm64-v8a" ]]; then
  echo "production bundle must preserve the accepted API26/arm64-v8a build" >&2
  exit 2
fi

toolchain="$NDK_ROOT/build/cmake/android.toolchain.cmake"
llvm_root="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
readelf="$llvm_root/bin/llvm-readelf"
android_clang="$llvm_root/bin/aarch64-linux-android${ANDROID_API}-clang"
zipalign="$ANDROID_HOME/build-tools/37.0.0/zipalign"
for path in "$toolchain" "$readelf" "$android_clang" "$zipalign"; do
  test -f "$path"
done

work_root="$RUNNER_TEMP/meteoone-grib-native"
src_root="$work_root/src"
build_root="$work_root/build"
prefix_root="$work_root/prefix"
aec_prefix="$prefix_root/libaec"
eccodes_prefix="$prefix_root/eccodes"
bundle_root="$OUTPUT_DIR/bundle"
evidence_root="$OUTPUT_DIR/evidence"
rm -rf "$work_root" "$OUTPUT_DIR"
mkdir -p "$src_root" "$build_root" "$prefix_root" \
  "$bundle_root/jniLibs/$ANDROID_ABI" "$bundle_root/assets" "$bundle_root/licenses" \
  "$evidence_root"

readarray -t pins < <(python3 - <<'PY'
import json
from pathlib import Path
pins = json.loads(Path("research/grib_decoder_candidates/pins.json").read_text())["candidates"]
for name in ("eccodes", "libaec"):
    print(pins[name]["repository"])
    print(pins[name]["commit"])
PY
)
ECCODES_REPOSITORY="${pins[0]}"
ECCODES_COMMIT="${pins[1]}"
LIBAEC_REPOSITORY="${pins[2]}"
LIBAEC_COMMIT="${pins[3]}"

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
  printf 'android_api\t%s\n' "$ANDROID_API"
  printf 'android_abi\t%s\n' "$ANDROID_ABI"
  cmake --version | head -n 1
  "$llvm_root/bin/clang" --version | head -n 1
} > "$evidence_root/toolchain.tsv"

cmake -S "$src_root/libaec" -B "$build_root/libaec" \
  -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$aec_prefix" \
  -DBUILD_SHARED_LIBS=ON \
  -DBUILD_STATIC_LIBS=OFF \
  -DBUILD_TESTING=OFF \
  2>&1 | tee "$evidence_root/libaec-configure.log"
cmake --build "$build_root/libaec" --target install --parallel 2 \
  2>&1 | tee "$evidence_root/libaec-build.log"

cmake -S "$src_root/eccodes" -B "$build_root/eccodes" \
  -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DDISABLE_OS_CHECK=ON \
  -DIEEE_BE=0 \
  -DIEEE_LE=1 \
  -DCMAKE_INSTALL_PREFIX="$eccodes_prefix" \
  -DCMAKE_MODULE_PATH="$src_root/ecbuild/cmake" \
  -DCMAKE_PREFIX_PATH="$aec_prefix" \
  -DCMAKE_FIND_ROOT_PATH="$aec_prefix" \
  -DBUILD_SHARED_LIBS=ON \
  -DENABLE_PRODUCT_GRIB=ON \
  -DENABLE_PRODUCT_BUFR=OFF \
  -DENABLE_AEC=ON \
  -DENABLE_USE_SHARED_LIB_AEC=ON \
  -DENABLE_EXAMPLES=OFF \
  -DENABLE_BUILD_TOOLS=OFF \
  -DENABLE_TESTS=OFF \
  -DENABLE_GEOGRAPHY=OFF \
  -DENABLE_JPG=OFF \
  -DENABLE_PNG=OFF \
  -DENABLE_NETCDF=OFF \
  -DENABLE_FORTRAN=OFF \
  -DENABLE_PYTHON=OFF \
  -DENABLE_MEMFS=OFF \
  -DENABLE_INSTALL_ECCODES_DEFINITIONS=ON \
  -DENABLE_INSTALL_ECCODES_SAMPLES=OFF \
  2>&1 | tee "$evidence_root/eccodes-configure.log"
cmake --build "$build_root/eccodes" --target install --parallel 2 \
  2>&1 | tee "$evidence_root/eccodes-build.log"

bridge_library="$build_root/libmeteoone_grib_jni.so"
"$android_clang" \
  -std=c11 \
  -O2 \
  -fPIC \
  -Wall \
  -Wextra \
  -Werror \
  -I"$eccodes_prefix/include" \
  -shared \
  -Wl,--no-undefined \
  -Wl,-z,max-page-size=16384 \
  -Wl,-soname,libmeteoone_grib_jni.so \
  forecast/data/native/meteoone_grib_jni.c \
  -L"$eccodes_prefix/lib" \
  -leccodes \
  -lm \
  -o "$bridge_library" \
  2>&1 | tee "$evidence_root/jni-bridge-build.log"

libraries=(
  "$eccodes_prefix/lib/libeccodes.so"
  "$aec_prefix/lib/libaec.so"
  "$aec_prefix/lib/libsz.so"
  "$bridge_library"
)
: > "$evidence_root/native-elf.txt"
: > "$evidence_root/native-sizes.tsv"
for library in "${libraries[@]}"; do
  test -f "$library"
  name="$(basename "$library")"
  cp -L "$library" "$bundle_root/jniLibs/$ANDROID_ABI/$name"
  printf '%s\t%s\n' "$name" "$(stat -c '%s' "$library")" >> "$evidence_root/native-sizes.tsv"
  {
    printf '\n===== %s =====\n' "$name"
    "$readelf" -h "$library"
    "$readelf" -d "$library"
    "$readelf" -lW "$library"
  } >> "$evidence_root/native-elf.txt"
  "$readelf" -h "$library" | grep -Eq 'Machine:[[:space:]]+AArch64'
  mapfile -t alignments < <("$readelf" -lW "$library" | awk '$1 == "LOAD" {print $NF}')
  (( ${#alignments[@]} > 0 ))
  for alignment in "${alignments[@]}"; do
    if (( alignment < 0x4000 )); then
      printf '%s PT_LOAD alignment %s is below 0x4000\n' "$name" "$alignment" >&2
      exit 1
    fi
  done
done

# Ensure the packaged native dependency closure is explicit. Android system libraries
# are allowed; every third-party DT_NEEDED entry must be present in jniLibs.
python3 - "$readelf" "$bundle_root/jniLibs/$ANDROID_ABI" <<'PY'
import re
import subprocess
import sys
from pathlib import Path
readelf = sys.argv[1]
root = Path(sys.argv[2])
packaged = {p.name for p in root.glob("*.so")}
system = {"libc.so", "libdl.so", "libm.so", "liblog.so"}
for library in sorted(root.glob("*.so")):
    out = subprocess.check_output([readelf, "-d", str(library)], text=True)
    needed = set(re.findall(r"Shared library: \[([^]]+)\]", out))
    missing = needed - packaged - system
    if missing:
        raise SystemExit(f"{library.name} has unstaged DT_NEEDED entries: {sorted(missing)}")
PY

definitions_dir="$(find "$eccodes_prefix" -type d -path '*/eccodes/definitions' -print -quit)"
test -n "$definitions_dir"
definitions_stage="$work_root/definitions"
mkdir -p "$definitions_stage"
cp -RL "$definitions_dir/." "$definitions_stage/"
# ZIP timestamps are normalized for stable packaging metadata.
find "$definitions_stage" -exec touch -h -t 198001010000 {} +
definitions_zip="$bundle_root/assets/eccodes-definitions.zip"
(
  cd "$definitions_stage"
  find . -type f -print | LC_ALL=C sort | zip -q -X "$definitions_zip" -@
)
test -s "$definitions_zip"

cp "$src_root/eccodes/LICENSE" "$bundle_root/licenses/eccodes-LICENSE"
cp "$src_root/eccodes/NOTICE" "$bundle_root/licenses/eccodes-NOTICE"
cp "$src_root/libaec/LICENSE.txt" "$bundle_root/licenses/libaec-LICENSE.txt"

package_root="$work_root/package"
mkdir -p "$package_root/lib/$ANDROID_ABI"
cp "$bundle_root/jniLibs/$ANDROID_ABI/"*.so "$package_root/lib/$ANDROID_ABI/"
unaligned="$work_root/native-unaligned.apk"
aligned="$work_root/native-aligned.apk"
(cd "$package_root" && zip -q -0 -r "$unaligned" lib)
"$zipalign" -P 16 -f -v 4 "$unaligned" "$aligned" > "$evidence_root/zipalign-create.txt"
"$zipalign" -P 16 -c -v 4 "$aligned" > "$evidence_root/zipalign-verify.txt"
unzip -l "$aligned" > "$evidence_root/native-package-contents.txt"
sha256sum "$aligned" > "$evidence_root/native-package-sha256.txt"

python3 - "$bundle_root" "$ECCODES_COMMIT" "$LIBAEC_COMMIT" "$ECBUILD_COMMIT" "$ANDROID_API" "$ANDROID_ABI" <<'PY'
import hashlib
import json
import sys
from pathlib import Path
root = Path(sys.argv[1])
files = {}
for path in sorted(p for p in root.rglob("*") if p.is_file()):
    payload = path.read_bytes()
    files[path.relative_to(root).as_posix()] = {
        "bytes": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
    }
manifest = {
    "schema_version": 1,
    "eccodes_commit": sys.argv[2],
    "libaec_commit": sys.argv[3],
    "ecbuild_commit": sys.argv[4],
    "android_api": int(sys.argv[5]),
    "android_abi": sys.argv[6],
    "memfs": False,
    "flexible_page_sizes": True,
    "files": files,
}
(root / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
PY

find "$bundle_root" -type f -print0 | sort -z | xargs -0 sha256sum > "$evidence_root/bundle-sha256.txt"
find "$bundle_root" -type f -printf '%P\t%s\n' | sort > "$evidence_root/bundle-files.tsv"
