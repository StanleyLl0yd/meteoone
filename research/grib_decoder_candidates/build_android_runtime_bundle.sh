#!/usr/bin/env bash
set -euo pipefail

required_env=(
  SRC_ROOT
  NDK_ROOT
  ANDROID_API
  PREPARED_DIR
  NOAA_CONCAT
  EVIDENCE_DIR
  RUNNER_TEMP
  GITHUB_ENV
)
for name in "${required_env[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    printf 'required environment variable is unset: %s\n' "$name" >&2
    exit 2
  fi
done

build_root="$RUNNER_TEMP/grib-decoder-build"
prefix_root="$RUNNER_TEMP/grib-decoder-prefix"
runtime_root="$RUNNER_TEMP/grib-decoder-android-runtime"
x86_aec="$prefix_root/android-x86-libaec"
x86_eccodes="$prefix_root/android-x86-eccodes"
toolchain="$NDK_ROOT/build/cmake/android.toolchain.cmake"
readelf="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
clang="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android${ANDROID_API}-clang"
clangxx="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android${ANDROID_API}-clang++"

for path in "$toolchain" "$readelf" "$clang" "$clangxx"; do
  test -f "$path"
done

rm -rf \
  "$build_root/android-x86-libaec" \
  "$build_root/android-x86-eccodes" \
  "$x86_aec" \
  "$x86_eccodes" \
  "$runtime_root"
mkdir -p "$runtime_root"/{bin,lib,definitions,samples,malformed}

cmake -S "$SRC_ROOT/libaec" -B "$build_root/android-x86-libaec" \
  -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
  -DANDROID_ABI=x86_64 \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$x86_aec" \
  -DBUILD_SHARED_LIBS=ON \
  -DBUILD_STATIC_LIBS=OFF \
  -DBUILD_TESTING=OFF \
  2>&1 | tee "$EVIDENCE_DIR/android-x86-libaec-configure.log"
cmake --build "$build_root/android-x86-libaec" --target install --parallel 2 \
  2>&1 | tee "$EVIDENCE_DIR/android-x86-libaec-build.log"

cmake -S "$SRC_ROOT/eccodes" -B "$build_root/android-x86-eccodes" \
  -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
  -DANDROID_ABI=x86_64 \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DDISABLE_OS_CHECK=ON \
  -DIEEE_BE=0 \
  -DIEEE_LE=1 \
  -DCMAKE_INSTALL_PREFIX="$x86_eccodes" \
  -DCMAKE_MODULE_PATH="$SRC_ROOT/ecbuild/cmake" \
  -DCMAKE_PREFIX_PATH="$x86_aec" \
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
  2>&1 | tee "$EVIDENCE_DIR/android-x86-eccodes-configure.log"
cmake --build "$build_root/android-x86-eccodes" --target install --parallel 2 \
  2>&1 | tee "$EVIDENCE_DIR/android-x86-eccodes-build.log"

eccodes_library="$(find -L "$x86_eccodes" -type f -name 'libeccodes.so*' -print -quit)"
aec_library="$(find -L "$x86_aec" -type f -name 'libaec.so*' -print -quit)"
definitions_dir="$(find "$x86_eccodes" -type d -path '*/eccodes/definitions' -print -quit)"
test -n "$eccodes_library"
test -n "$aec_library"
test -n "$definitions_dir"
eccodes_lib_dir="$(dirname "$eccodes_library")"
aec_lib_dir="$(dirname "$aec_library")"

smoke_object="$RUNNER_TEMP/eccodes-smoke-android-x86_64.o"
"$clang" -std=c11 -O2 -Wall -Wextra -Wpedantic -Werror \
  -I"$x86_eccodes/include" \
  -c research/grib_decoder_candidates/eccodes_smoke.c \
  -o "$smoke_object"
"$clangxx" "$smoke_object" \
  -L"$eccodes_lib_dir" \
  -L"$aec_lib_dir" \
  -Wl,-rpath-link,"$eccodes_lib_dir" \
  -Wl,-rpath-link,"$aec_lib_dir" \
  -leccodes -laec -lm \
  -o "$runtime_root/bin/eccodes_smoke"

while IFS= read -r object; do
  cp -L "$object" "$runtime_root/lib/$(basename "$object")"
done < <(
  find -L "$x86_aec" "$x86_eccodes" -type f -name '*.so*' -print | sort
)
if ! compgen -G "$runtime_root/lib/*.so*" > /dev/null; then
  echo 'no x86_64 Android shared objects were staged' >&2
  exit 1
fi

# adb push does not need to preserve source-tree symlinks. Materialize the
# exact pinned ecCodes definitions as ordinary files for deterministic lookup.
cp -RL "$definitions_dir/." "$runtime_root/definitions/"

representatives=(
  dwd_precipitation.grib2
  dwd_temperature.grib2
  ecmwf_precipitation.grib2
  ecmwf_temperature.grib2
  noaa_precipitation.grib2
  noaa_temperature.grib2
)
for sample in "${representatives[@]}"; do
  test -f "$PREPARED_DIR/$sample"
  cp "$PREPARED_DIR/$sample" "$runtime_root/samples/$sample"
done
cp "$NOAA_CONCAT" "$runtime_root/samples/noaa_concatenated.grib2"

python - "$PREPARED_DIR/ecmwf_temperature.grib2" \
  "$runtime_root/malformed/truncated-drt42.grib2" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
payload = source.read_bytes()
if len(payload) < 64:
    raise SystemExit("DRT42 seed unexpectedly small")
target.write_bytes(payload[: len(payload) // 2])
PY

report="$EVIDENCE_DIR/android-x86-runtime-elf-report.txt"
sizes="$EVIDENCE_DIR/android-x86-runtime-native-sizes.tsv"
: > "$report"
: > "$sizes"
mapfile -t runtime_elfs < <(
  find "$runtime_root/bin" "$runtime_root/lib" -type f -print | sort
)
for object in "${runtime_elfs[@]}"; do
  printf '%s\t%s\n' "$object" "$(stat -c '%s' "$object")" >> "$sizes"
  {
    printf '\n===== %s =====\n' "$object"
    "$readelf" -h "$object"
    "$readelf" -d "$object"
    "$readelf" -lW "$object"
  } >> "$report"
  "$readelf" -h "$object" | grep -Eq 'Machine:[[:space:]]+Advanced Micro Devices X86-64'
  mapfile -t alignments < <("$readelf" -lW "$object" | awk '$1 == "LOAD" {print $NF}')
  if (( ${#alignments[@]} == 0 )); then
    printf '%s has no PT_LOAD segments\n' "$object" >&2
    exit 1
  fi
  for alignment in "${alignments[@]}"; do
    if (( alignment < 0x4000 )); then
      printf '%s PT_LOAD alignment %s is below 0x4000\n' "$object" "$alignment" >&2
      exit 1
    fi
  done
done

{
  printf 'android_api\t%s\n' "$ANDROID_API"
  printf 'android_abi\tx86_64\n'
  "$clang" --version | head -n 1
  "$clangxx" --version | head -n 1
} > "$EVIDENCE_DIR/android-x86-runtime-toolchain.txt"

find "$runtime_root/bin" "$runtime_root/lib" "$runtime_root/samples" "$runtime_root/malformed" \
  -type f -print0 | sort -z | xargs -0 sha256sum \
  > "$EVIDENCE_DIR/android-x86-runtime-bundle-sha256.txt"

printf 'ANDROID_RUNTIME_BUNDLE=%s\n' "$runtime_root" >> "$GITHUB_ENV"
