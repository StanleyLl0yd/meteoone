from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path
from typing import Iterable

from .model import Forecast, Location
from .providers import (
    OPEN_METEO_MODELS,
    MetNorwayAdapter,
    OpenMeteoAdapter,
)


ROOT = Path(__file__).resolve().parent
DEFAULT_LOCATIONS = ROOT / "locations.json"


def load_locations(path: Path = DEFAULT_LOCATIONS) -> list[Location]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    return [Location(**item) for item in raw]


def _find_location(locations: Iterable[Location], location_id: str) -> Location:
    for location in locations:
        if location.id == location_id:
            return location
    raise SystemExit(f"Unknown location id: {location_id}")


def _write_forecasts(forecasts: Iterable[Forecast], output: Path | None) -> None:
    text = "\n".join(
        json.dumps(forecast.to_dict(), ensure_ascii=False, sort_keys=True)
        for forecast in forecasts
    )
    if output is None:
        print(text)
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text + ("\n" if text else ""), encoding="utf-8")


def live(args: argparse.Namespace) -> None:
    location = _find_location(load_locations(), args.location)
    open_meteo = OpenMeteoAdapter()
    forecasts = [
        open_meteo.fetch_live(location, model, args.hours)
        for model in OPEN_METEO_MODELS
    ]
    if not args.no_met_norway:
        forecasts.append(MetNorwayAdapter().fetch_live(location, args.hours))
    _write_forecasts(forecasts, args.output)


def single_run(args: argparse.Namespace) -> None:
    location = _find_location(load_locations(), args.location)
    adapter = OpenMeteoAdapter()
    forecasts = [
        adapter.fetch_single_run(location, model, args.run, args.hours)
        for model in OPEN_METEO_MODELS
    ]
    _write_forecasts(forecasts, args.output)


def list_locations(_: argparse.Namespace) -> None:
    for location in load_locations():
        print(
            f"{location.id:18} "
            f"{location.latitude:8.4f} {location.longitude:9.4f} "
            f"{location.name}"
        )


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        description="MeteoOne M0 forecast research harness"
    )
    sub = root.add_subparsers(dest="command", required=True)

    locations = sub.add_parser("locations", help="list benchmark locations")
    locations.set_defaults(handler=list_locations)

    live_parser = sub.add_parser(
        "live",
        help="collect current ECMWF/ICON/GFS and optional MET Norway forecasts",
    )
    live_parser.add_argument("--location", required=True)
    live_parser.add_argument("--hours", type=int, default=72)
    live_parser.add_argument("--no-met-norway", action="store_true")
    live_parser.add_argument("--output", type=Path)
    live_parser.set_defaults(handler=live)

    run_parser = sub.add_parser(
        "single-run",
        help="collect one archived Open-Meteo model run for ECMWF/ICON/GFS",
    )
    run_parser.add_argument("--location", required=True)
    run_parser.add_argument(
        "--run",
        required=True,
        help="model initialization time, e.g. 2026-09-01T00:00Z",
    )
    run_parser.add_argument("--hours", type=int, default=72)
    run_parser.add_argument("--output", type=Path)
    run_parser.set_defaults(handler=single_run)

    return root


def main() -> None:
    args = parser().parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
