#!/usr/bin/env bash
set -euo pipefail

if (( $# != 3 )); then
  echo "usage: $0 LABEL EXPECTED_SDK EXPECTED_PAGE_SIZE_OR_ANY" >&2
  exit 2
fi

label="$1"
expected_sdk="$2"
expected_page_size="$3"
if [[ ! "$label" =~ ^[a-z0-9-]+$ ]]; then
  echo "unsafe runtime label: $label" >&2
  exit 2
fi
if [[ ! "$expected_sdk" =~ ^[0-9]+$ ]]; then
  echo "invalid expected SDK: $expected_sdk" >&2
  exit 2
fi
if [[ "$expected_page_size" != "any" && ! "$expected_page_size" =~ ^[0-9]+$ ]]; then
  echo "invalid expected page size: $expected_page_size" >&2
  exit 2
fi

: "${ANDROID_RUNTIME_BUNDLE:?ANDROID_RUNTIME_BUNDLE is required}"
: "${EVIDENCE_DIR:?EVIDENCE_DIR is required}"
test -d "$ANDROID_RUNTIME_BUNDLE"
mkdir -p "$EVIDENCE_DIR"

adb wait-for-device
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
architecture="$(adb shell uname -m | tr -d '\r')"
if page_size_probe="$(adb shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')" \
  && [[ "$page_size_probe" =~ ^[0-9]+$ ]]; then
  page_size="$page_size_probe"
else
  page_size="unavailable"
fi
fingerprint="$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
{
  printf 'label\t%s\n' "$label"
  printf 'sdk\t%s\n' "$sdk"
  printf 'page_size\t%s\n' "$page_size"
  printf 'architecture\t%s\n' "$architecture"
  printf 'fingerprint\t%s\n' "$fingerprint"
} | tee "$EVIDENCE_DIR/android-runtime-${label}-environment.tsv"

if [[ "$sdk" != "$expected_sdk" ]]; then
  printf 'expected Android SDK %s, got %s\n' "$expected_sdk" "$sdk" >&2
  exit 1
fi
if [[ "$architecture" != "x86_64" ]]; then
  printf 'expected x86_64 runtime, got %s\n' "$architecture" >&2
  exit 1
fi
if [[ "$expected_page_size" != "any" && "$page_size" != "$expected_page_size" ]]; then
  printf 'expected page size %s, got %s\n' "$expected_page_size" "$page_size" >&2
  exit 1
fi

remote_root="/data/local/tmp/meteoone-grib-${label}"
cleanup() {
  adb shell rm -rf "$remote_root" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup
adb shell mkdir -p "$remote_root"
adb push "$ANDROID_RUNTIME_BUNDLE/bin" "$remote_root/" >/dev/null
adb push "$ANDROID_RUNTIME_BUNDLE/lib" "$remote_root/" >/dev/null
adb push "$ANDROID_RUNTIME_BUNDLE/definitions" "$remote_root/" >/dev/null
adb push "$ANDROID_RUNTIME_BUNDLE/samples" "$remote_root/" >/dev/null
adb push "$ANDROID_RUNTIME_BUNDLE/malformed" "$remote_root/" >/dev/null
adb shell chmod 755 "$remote_root/bin/eccodes_smoke"

runtime_prefix="LD_LIBRARY_PATH=$remote_root/lib ECCODES_DEFINITION_PATH=$remote_root/definitions $remote_root/bin/eccodes_smoke"
representatives=(
  dwd_precipitation.grib2
  dwd_temperature.grib2
  ecmwf_precipitation.grib2
  ecmwf_temperature.grib2
  noaa_precipitation.grib2
  noaa_temperature.grib2
)
remote_representatives=()
for sample in "${representatives[@]}"; do
  remote_representatives+=("$remote_root/samples/$sample")
done

# Paths are generated internally and contain no shell metacharacters/spaces.
representative_command="$runtime_prefix ${remote_representatives[*]}"
adb shell "$representative_command" | tr -d '\r' \
  | tee "$EVIDENCE_DIR/android-runtime-${label}-representative-decode.txt"
for expected in gdt=0 gdt=101 pdt=0 pdt=8 drt=0 drt=42; do
  grep -q "$expected" "$EVIDENCE_DIR/android-runtime-${label}-representative-decode.txt"
done

concat_output="$EVIDENCE_DIR/android-runtime-${label}-noaa-concatenated-decode.txt"
adb shell "$runtime_prefix $remote_root/samples/noaa_concatenated.grib2" | tr -d '\r' \
  | tee "$concat_output"
awk '
  /^summary / {
    for (i = 1; i <= NF; ++i) {
      if ($i ~ /^messages=/) {
        split($i, parts, "=");
        if (parts[2] + 0 >= 2) ok = 1;
      }
    }
  }
  END { exit ok ? 0 : 1 }
' "$concat_output"

malformed_stdout="$EVIDENCE_DIR/android-runtime-${label}-malformed-drt42.stdout.txt"
malformed_stderr="$EVIDENCE_DIR/android-runtime-${label}-malformed-drt42.stderr.txt"
set +e
adb shell "$runtime_prefix $remote_root/malformed/truncated-drt42.grib2" \
  > "$malformed_stdout" 2> "$malformed_stderr"
status=$?
set -e
tr -d '\r' < "$malformed_stdout" > "$malformed_stdout.tmp"
mv "$malformed_stdout.tmp" "$malformed_stdout"
tr -d '\r' < "$malformed_stderr" > "$malformed_stderr.tmp"
mv "$malformed_stderr.tmp" "$malformed_stderr"
printf 'exit_status\t%s\n' "$status" \
  | tee "$EVIDENCE_DIR/android-runtime-${label}-malformed-drt42-status.tsv"
if (( status != 1 )); then
  printf 'expected controlled decoder failure status 1, got %s\n' "$status" >&2
  exit 1
fi
