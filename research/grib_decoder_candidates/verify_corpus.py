from __future__ import annotations

import argparse
import json
from pathlib import Path

from .corpus import prepare_representative_samples, verify_corpus


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--prepare", type=Path)
    args = parser.parse_args()

    result = verify_corpus(args.corpus)
    payload: dict[str, object] = {
        "file_count": result.file_count,
        "sample_count": result.sample_count,
        "ecmwf_index_sha256": result.index_sha256,
    }
    if args.prepare is not None:
        payload["prepared_samples"] = {
            name: str(path)
            for name, path in prepare_representative_samples(
                args.corpus, args.prepare
            ).items()
        }
    print(json.dumps(payload, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
