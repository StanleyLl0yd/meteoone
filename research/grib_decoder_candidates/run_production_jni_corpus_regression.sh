#!/usr/bin/env bash
set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${JAVA_HOME:?JAVA_HOME is required}"

EVIDENCE_DIR="${EVIDENCE_DIR:-research-output/grib-production-jni}"
mkdir -p "$EVIDENCE_DIR"

for command in gh git cmake cc javac java python3 unzip sha256sum; do
  command -v "$command" >/dev/null
done

readarray -t pins < <(python3 - <<'PY'
import json
from pathlib import Path

pins = json.loads(Path("research/grib_decoder_candidates/pins.json").read_text())
corpus = pins["corpus"]
candidates = pins["candidates"]
for value in (
    corpus["workflow_run_id"],
    corpus["artifact_id"],
    corpus["artifact_name"],
    corpus["artifact_digest"],
    corpus["source_head_sha"],
    candidates["eccodes"]["repository"],
    candidates["eccodes"]["commit"],
    candidates["libaec"]["repository"],
    candidates["libaec"]["commit"],
):
    print(value)
PY
)
if (( ${#pins[@]} != 9 )); then
  echo "unexpected GRIB regression pin count" >&2
  exit 1
fi
CORPUS_RUN_ID="${pins[0]}"
CORPUS_ARTIFACT_ID="${pins[1]}"
CORPUS_ARTIFACT_NAME="${pins[2]}"
CORPUS_ARTIFACT_DIGEST="${pins[3]}"
CORPUS_SOURCE_SHA="${pins[4]}"
ECCODES_REPOSITORY="${pins[5]}"
ECCODES_COMMIT="${pins[6]}"
LIBAEC_REPOSITORY="${pins[7]}"
LIBAEC_COMMIT="${pins[8]}"
ECBUILD_REPOSITORY="https://github.com/ecmwf/ecbuild.git"
ECBUILD_COMMIT="60e7d659ec10a4316e0ec27be28254092dcd7921"

cp research/grib_decoder_candidates/pins.json "$EVIDENCE_DIR/pins.json"
gh api "/repos/$GITHUB_REPOSITORY/actions/artifacts/$CORPUS_ARTIFACT_ID" \
  > "$EVIDENCE_DIR/corpus-artifact-metadata.json"
gh api "/repos/$GITHUB_REPOSITORY/actions/runs/$CORPUS_RUN_ID" \
  > "$EVIDENCE_DIR/corpus-workflow-run.json"
python3 - \
  "$EVIDENCE_DIR/corpus-artifact-metadata.json" \
  "$EVIDENCE_DIR/corpus-workflow-run.json" \
  "$CORPUS_RUN_ID" \
  "$CORPUS_ARTIFACT_ID" \
  "$CORPUS_ARTIFACT_NAME" \
  "$CORPUS_ARTIFACT_DIGEST" \
  "$CORPUS_SOURCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

artifact = json.loads(Path(sys.argv[1]).read_text())
run = json.loads(Path(sys.argv[2]).read_text())
run_id = int(sys.argv[3])
artifact_id = int(sys.argv[4])
artifact_name = sys.argv[5]
artifact_digest = sys.argv[6]
source_sha = sys.argv[7]
expected = {"id": artifact_id, "name": artifact_name, "digest": artifact_digest}
for key, value in expected.items():
    if artifact.get(key) != value:
        raise SystemExit(f"artifact {key} drifted: {artifact.get(key)!r} != {value!r}")
if artifact.get("expired") is not False:
    raise SystemExit("pinned corpus artifact is expired")
workflow_run = artifact.get("workflow_run") or {}
if workflow_run.get("id") != run_id or workflow_run.get("head_sha") != source_sha:
    raise SystemExit("artifact workflow provenance drifted")
if run.get("id") != run_id or run.get("head_sha") != source_sha:
    raise SystemExit("workflow run provenance drifted")
if run.get("conclusion") != "success":
    raise SystemExit(f"pinned workflow conclusion is {run.get('conclusion')!r}")
PY

corpus_zip="$RUNNER_TEMP/meteoone-grib-production-corpus.zip"
gh api "/repos/$GITHUB_REPOSITORY/actions/artifacts/$CORPUS_ARTIFACT_ID/zip" > "$corpus_zip"
test -s "$corpus_zip"
unpacked="$RUNNER_TEMP/meteoone-grib-production-corpus"
prepared="$RUNNER_TEMP/meteoone-grib-production-prepared"
rm -rf "$unpacked" "$prepared"
mkdir -p "$unpacked"
unzip -q "$corpus_zip" -d "$unpacked"
mapfile -t manifests < <(find "$unpacked" -type f -name SHA256SUMS -print)
if (( ${#manifests[@]} != 1 )); then
  printf 'expected one corpus SHA256SUMS, found %s\n' "${#manifests[@]}" >&2
  exit 1
fi
corpus_dir="$(dirname "${manifests[0]}")"
python3 -m research.grib_decoder_candidates.verify_corpus \
  "$corpus_dir" --prepare "$prepared" \
  | tee "$EVIDENCE_DIR/corpus-verification.json"

expected_tsv="$EVIDENCE_DIR/provider-envelopes.tsv"
python3 - "$corpus_dir/evidence.json" > "$expected_tsv" <<'PY'
import json
import sys
from pathlib import Path

evidence = json.loads(Path(sys.argv[1]).read_text())
representatives = {
    ("NOAA_NOMADS", "temperature_2m"): "noaa_temperature.grib2",
    ("NOAA_NOMADS", "total_precipitation"): "noaa_precipitation.grib2",
    ("ECMWF_OPEN_DATA", "temperature_2m"): "ecmwf_temperature.grib2",
    ("ECMWF_OPEN_DATA", "total_precipitation"): "ecmwf_precipitation.grib2",
    ("DWD_OPEN_DATA", "temperature_2m"): "dwd_temperature.grib2",
    ("DWD_OPEN_DATA", "total_precipitation"): "dwd_precipitation.grib2",
}
found = set()
for sample in evidence["samples"]:
    key = (sample["provider"], sample["field"])
    target = representatives.get(key)
    if target is None:
        continue
    if key in found:
        raise SystemExit(f"duplicate representative in immutable corpus: {key}")
    found.add(key)
    messages = sample.get("messages") or []
    if not messages:
        raise SystemExit(f"representative has no GRIB messages: {key}")
    for index, message in enumerate(messages):
        values = (
            target,
            index,
            message["edition"],
            message["discipline"],
            message["parameter_category"],
            message["parameter_number"],
            message["product_definition_template"],
            message["grid_definition_template"],
            message["data_representation_template"],
        )
        print("\t".join(map(str, values)))
missing = set(representatives) - found
if missing:
    raise SystemExit(f"immutable corpus is missing representatives: {sorted(missing)}")
PY

unique_representatives="$(cut -f1 "$expected_tsv" | sort -u | wc -l)"
if (( unique_representatives != 6 )); then
  printf 'expected six JNI representatives, found %s\n' "$unique_representatives" >&2
  exit 1
fi

src_root="$RUNNER_TEMP/meteoone-grib-production-src"
build_root="$RUNNER_TEMP/meteoone-grib-production-build"
prefix_root="$RUNNER_TEMP/meteoone-grib-production-prefix"
rm -rf "$src_root" "$build_root" "$prefix_root"
mkdir -p "$src_root" "$build_root" "$prefix_root"

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

host_aec="$prefix_root/libaec"
host_eccodes="$prefix_root/eccodes"
cmake -S "$src_root/libaec" -B "$build_root/libaec" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$host_aec" \
  -DBUILD_SHARED_LIBS=ON \
  -DBUILD_STATIC_LIBS=OFF \
  -DBUILD_TESTING=OFF \
  2>&1 | tee "$EVIDENCE_DIR/host-libaec-build.log"
cmake --build "$build_root/libaec" --target install --parallel 2 \
  2>&1 | tee -a "$EVIDENCE_DIR/host-libaec-build.log"

cmake -S "$src_root/eccodes" -B "$build_root/eccodes" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$host_eccodes" \
  -DCMAKE_MODULE_PATH="$src_root/ecbuild/cmake" \
  -DCMAKE_PREFIX_PATH="$host_aec" \
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
  2>&1 | tee "$EVIDENCE_DIR/host-eccodes-build.log"
cmake --build "$build_root/eccodes" --target install --parallel 2 \
  2>&1 | tee -a "$EVIDENCE_DIR/host-eccodes-build.log"

eccodes_library="$(find -L "$host_eccodes" -type f -name 'libeccodes.so*' -print -quit)"
aec_library="$(find -L "$host_aec" -type f -name 'libaec.so*' -print -quit)"
definitions_dir="$(find "$host_eccodes" -type d -path '*/eccodes/definitions' -print -quit)"
test -n "$eccodes_library"
test -n "$aec_library"
test -n "$definitions_dir"
eccodes_lib_dir="$(dirname "$eccodes_library")"
aec_lib_dir="$(dirname "$aec_library")"

jni_library="$build_root/libmeteoone_grib_jni.so"
cc -std=c11 -O2 -fPIC -Wall -Wextra -Werror \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/linux" \
  -I"$host_eccodes/include" \
  -shared \
  -Wl,--no-undefined \
  -Wl,-rpath-link,"$eccodes_lib_dir" \
  -Wl,-rpath-link,"$aec_lib_dir" \
  -Wl,-soname,libmeteoone_grib_jni.so \
  forecast/data/native/meteoone_grib_jni.c \
  -L"$eccodes_lib_dir" \
  -leccodes \
  -lm \
  -o "$jni_library" \
  2>&1 | tee "$EVIDENCE_DIR/production-jni-build.log"

classes="$build_root/java-classes"
mkdir -p "$classes"
javac -d "$classes" \
  research/grib_decoder_candidates/production_jni/ProductionJniCorpusRegression.java

export LD_LIBRARY_PATH="$eccodes_lib_dir:$aec_lib_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
java -cp "$classes" \
  com.sl.meteoone.forecast.data.grib.ProductionJniCorpusRegression \
  "$jni_library" \
  "$definitions_dir" \
  "$prepared" \
  "$expected_tsv" \
  | tee "$EVIDENCE_DIR/production-jni-corpus-regression.txt"

{
  printf 'eccodes\t%s\n' "$ECCODES_COMMIT"
  printf 'libaec\t%s\n' "$LIBAEC_COMMIT"
  printf 'ecbuild\t%s\n' "$ECBUILD_COMMIT"
  printf 'corpus_run\t%s\n' "$CORPUS_RUN_ID"
  printf 'corpus_artifact\t%s\n' "$CORPUS_ARTIFACT_ID"
  printf 'corpus_source\t%s\n' "$CORPUS_SOURCE_SHA"
  java -version 2>&1 | head -n 1
  cc --version | head -n 1
  cmake --version | head -n 1
} > "$EVIDENCE_DIR/toolchain-and-pins.tsv"
sha256sum \
  forecast/data/native/meteoone_grib_jni.c \
  research/grib_decoder_candidates/production_jni/ProductionJniCorpusRegression.java \
  "$jni_library" \
  > "$EVIDENCE_DIR/production-jni-sha256.txt"
