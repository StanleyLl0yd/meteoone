from __future__ import annotations

import argparse
import json
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Iterable

from .benchmark import score_forecasts
from .collector import collect_archived_runs, iter_runs
from .model import Forecast, Location
from .observations import NceiIsdAdapter, ObservationSeries
from .providers import (
    OPEN_METEO_MODELS,
    MetNorwayAdapter,
    OpenMeteoAdapter,
)
from .storage import (
    read_forecasts,
    read_observations,
    write_forecasts,
    write_observations,
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
    values = tuple(forecasts)
    if output is not None:
        write_forecasts(output, values)
        return
    print(
        "\n".join(
            json.dumps(forecast.to_dict(), ensure_ascii=False, sort_keys=True)
            for forecast in values
        )
    )


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


def batch_runs(args: argparse.Namespace) -> None:
    locations = load_locations()
    selected = (
        [_find_location(locations, location_id) for location_id in args.location]
        if args.location
        else locations
    )
    result = collect_archived_runs(
        OpenMeteoAdapter(),
        selected,
        iter_runs(args.start, args.end, cycle_hours=args.cycles),
        forecast_hours=args.hours,
    )
    write_forecasts(args.output, result.forecasts)

    errors_path = args.errors or args.output.with_name(
        f"{args.output.stem}-errors.jsonl"
    )
    errors_path.parent.mkdir(parents=True, exist_ok=True)
    error_lines = [
        json.dumps(error.to_dict(), ensure_ascii=False, sort_keys=True)
        for error in result.errors
    ]
    errors_path.write_text(
        "\n".join(error_lines) + ("\n" if error_lines else ""),
        encoding="utf-8",
    )
    print(
        f"forecasts={len(result.forecasts)} errors={len(result.errors)} "
        f"output={args.output} errors_output={errors_path}"
    )


def station(args: argparse.Namespace) -> None:
    location = _find_location(load_locations(), args.location)
    match = _find_station_match(args, location)
    payload = match.station.to_dict()
    payload["distance_km"] = round(match.distance_km, 3)
    payload["elevation_delta_m"] = match.elevation_delta_m
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2))


def observations(args: argparse.Namespace) -> None:
    location = _find_location(load_locations(), args.location)
    adapter = NceiIsdAdapter()
    match = adapter.find_station(
        location,
        args.start,
        args.end,
        max_distance_km=args.max_distance_km,
        target_elevation_m=args.target_elevation_m,
        max_elevation_delta_m=args.max_elevation_delta_m,
    )

    points_by_time = {}
    for year in range(args.start.year, args.end.year + 1):
        series = adapter.fetch_year(match.station, year)
        for point in series.points:
            point_date = _point_date(point.time)
            if args.start <= point_date <= args.end:
                points_by_time[point.time] = point

    series = ObservationSeries(
        source="NOAA_NCEI_ISD",
        station=match.station,
        points=tuple(points_by_time[key] for key in sorted(points_by_time)),
    )
    write_observations(args.output, series)
    print(
        f"station={match.station.station_id} "
        f"distance_km={match.distance_km:.1f} "
        f"points={len(series.points)} output={args.output}"
    )


def score(args: argparse.Namespace) -> None:
    location = _find_location(load_locations(), args.location)
    forecasts = tuple(
        forecast
        for forecast in read_forecasts(args.forecasts)
        if forecast.location.id == location.id
    )
    if not forecasts:
        raise SystemExit(
            f"No forecasts for location {location.id} in {args.forecasts}"
        )

    observation_series = read_observations(args.observations)
    scores = score_forecasts(
        forecasts,
        observation_series,
        tolerance_minutes=args.tolerance_minutes,
    )
    payload = {
        "location": location.id,
        "forecast_count": len(forecasts),
        "observation_source": observation_series.source,
        "station": observation_series.station.to_dict(),
        "scores": [value.to_dict() for value in scores],
    }
    text = json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2)
    if args.output is None:
        print(text)
        return
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text + "\n", encoding="utf-8")


def list_locations(_: argparse.Namespace) -> None:
    for location in load_locations():
        print(
            f"{location.id:18} "
            f"{location.latitude:8.4f} {location.longitude:9.4f} "
            f"{location.name}"
        )


def _find_station_match(
    args: argparse.Namespace,
    location: Location,
):
    return NceiIsdAdapter().find_station(
        location,
        args.start,
        args.end,
        max_distance_km=args.max_distance_km,
        target_elevation_m=args.target_elevation_m,
        max_elevation_delta_m=args.max_elevation_delta_m,
    )


def _date_arg(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            f"invalid ISO date {value!r}; expected YYYY-MM-DD"
        ) from error


def _cycles_arg(value: str) -> tuple[int, ...]:
    try:
        cycles = tuple(int(part.strip()) for part in value.split(","))
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            "cycles must be comma-separated UTC hours"
        ) from error

    if not cycles or any(hour < 0 or hour > 23 for hour in cycles):
        raise argparse.ArgumentTypeError("cycle hours must be within 0..23")
    if len(set(cycles)) != len(cycles):
        raise argparse.ArgumentTypeError("cycle hours must not contain duplicates")
    return cycles


def _point_date(value: str) -> date:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).date()


def _add_station_rules(target: argparse.ArgumentParser) -> None:
    target.add_argument("--location", required=True)
    target.add_argument("--start", required=True, type=_date_arg)
    target.add_argument("--end", required=True, type=_date_arg)
    target.add_argument("--max-distance-km", type=float, default=75.0)
    target.add_argument("--target-elevation-m", type=float)
    target.add_argument("--max-elevation-delta-m", type=float, default=300.0)


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

    batch_parser = sub.add_parser(
        "batch-runs",
        help="collect archived Open-Meteo runs with per-source failure isolation",
    )
    batch_parser.add_argument("--start", required=True, type=_date_arg)
    batch_parser.add_argument("--end", required=True, type=_date_arg)
    batch_parser.add_argument(
        "--cycles",
        type=_cycles_arg,
        default=(0,),
        help="comma-separated UTC cycles; default: 0",
    )
    batch_parser.add_argument(
        "--location",
        action="append",
        help="benchmark location id; repeatable; omitted means all locations",
    )
    batch_parser.add_argument("--hours", type=int, default=72)
    batch_parser.add_argument("--output", required=True, type=Path)
    batch_parser.add_argument("--errors", type=Path)
    batch_parser.set_defaults(handler=batch_runs)

    station_parser = sub.add_parser(
        "station",
        help="select an NOAA/NCEI ISD station for a benchmark period",
    )
    _add_station_rules(station_parser)
    station_parser.set_defaults(handler=station)

    observations_parser = sub.add_parser(
        "observations",
        help="collect NOAA/NCEI ISD surface observations",
    )
    _add_station_rules(observations_parser)
    observations_parser.add_argument("--output", required=True, type=Path)
    observations_parser.set_defaults(handler=observations)

    score_parser = sub.add_parser(
        "score",
        help="score archived forecasts against collected observations",
    )
    score_parser.add_argument("--location", required=True)
    score_parser.add_argument("--forecasts", required=True, type=Path)
    score_parser.add_argument("--observations", required=True, type=Path)
    score_parser.add_argument("--tolerance-minutes", type=int, default=30)
    score_parser.add_argument("--output", type=Path)
    score_parser.set_defaults(handler=score)

    return root


def main() -> None:
    args = parser().parse_args()
    if hasattr(args, "start") and hasattr(args, "end") and args.start > args.end:
        raise SystemExit("--start must not be after --end")
    args.handler(args)


if __name__ == "__main__":
    main()
