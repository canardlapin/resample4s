from __future__ import annotations

import argparse
import csv
import platform
import statistics
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


PROTOCOL = "resample4s-benchmark/v1"


def parse_integer(value: str) -> int:
    return int(float(value))


@dataclass(frozen=True)
class Aggregate:
    case_id: str
    family: str
    contract_id: str
    library: str
    library_version: str
    runtime: str
    median_ns: int
    minimum_ns: int
    measurements: int
    units: int
    analysis_ordinals: int
    assessment_ordinals: int
    fixture_checksum: int
    quality_primary: int
    quality_secondary: int
    relative_to_resample4s: float


def load(paths: list[Path]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for path in paths:
        with path.open(newline="", encoding="utf-8") as handle:
            current = list(csv.DictReader(handle))
        if not current:
            raise ValueError(f"benchmark result is empty: {path}")
        rows.extend(current)
    return rows


def validate(rows: list[dict[str, str]]) -> None:
    for row in rows:
        if row["protocol"] != PROTOCOL:
            raise ValueError(
                f"{row['case_id']} has protocol {row['protocol']}"
            )
        if row["contract_ok"].lower() != "true":
            raise ValueError(
                f"{row['case_id']} / {row['library']} failed its contract"
            )
        if parse_integer(row["elapsed_ns"]) <= 0:
            raise ValueError(
                f"{row['case_id']} / {row['library']} has a "
                "non-positive elapsed time"
            )

    by_case: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        by_case[row["case_id"]].append(row)
    for case_id, case_rows in by_case.items():
        libraries = {row["library"] for row in case_rows}
        if "resample4s" not in libraries:
            raise ValueError(f"{case_id} has no Resample4s baseline")
        if len(libraries) < 2:
            raise ValueError(f"{case_id} has no external comparator")
        for field in ("family", "contract_id"):
            values = {row[field] for row in case_rows}
            if len(values) != 1:
                raise ValueError(
                    f"{case_id} libraries disagree on {field}: {values}"
                )
        for field in ("n", "folds", "repeats", "fixture_checksum"):
            values = {parse_integer(row[field]) for row in case_rows}
            if len(values) != 1:
                raise ValueError(
                    f"{case_id} libraries disagree on {field}: {values}"
                )

        by_library: dict[str, list[dict[str, str]]] = defaultdict(list)
        for row in case_rows:
            by_library[row["library"]].append(row)
        expected_measurements: tuple[int, ...] | None = None
        stable_fields = (
            "units",
            "analysis_ordinals",
            "assessment_ordinals",
            "semantic_checksum",
            "quality_primary",
            "quality_secondary",
        )
        for library, library_rows in by_library.items():
            measurements = tuple(
                sorted(parse_integer(row["measurement"]) for row in library_rows)
            )
            if measurements != tuple(range(len(library_rows))):
                raise ValueError(
                    f"{case_id} / {library} has a missing or duplicate "
                    "measurement index"
                )
            if expected_measurements is None:
                expected_measurements = measurements
            elif measurements != expected_measurements:
                raise ValueError(
                    f"{case_id} libraries use different measurement sets"
                )
            for field in stable_fields:
                values = {
                    parse_integer(row[field]) for row in library_rows
                }
                if len(values) != 1:
                    raise ValueError(
                        f"{case_id} / {library} has unstable {field}"
                    )

        representatives = {
            library: library_rows[0]
            for library, library_rows in by_library.items()
        }
        units = {
            parse_integer(row["units"]) for row in representatives.values()
        }
        analysis = {
            parse_integer(row["analysis_ordinals"])
            for row in representatives.values()
        }
        family = case_rows[0]["family"]
        if len(units) != 1 or len(analysis) != 1:
            raise ValueError(
                f"{case_id} libraries produced different artifact sizes"
            )
        if family != "bootstrap":
            assessment = {
                parse_integer(row["assessment_ordinals"])
                for row in representatives.values()
            }
            if len(assessment) != 1:
                raise ValueError(
                    f"{case_id} libraries produced different assessment sizes"
                )


def aggregate(rows: list[dict[str, str]]) -> list[Aggregate]:
    grouped: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        grouped[(row["case_id"], row["library"])].append(row)
    medians = {
        key: int(
            statistics.median(
                parse_integer(row["elapsed_ns"]) for row in group_rows
            )
        )
        for key, group_rows in grouped.items()
    }
    result: list[Aggregate] = []
    ordered = sorted(
        grouped.items(),
        key=lambda item: (
            item[0][0],
            item[0][1] != "resample4s",
            item[0][1],
        ),
    )
    for (case_id, library), group_rows in ordered:
        first = group_rows[0]
        baseline = medians[(case_id, "resample4s")]
        median = medians[(case_id, library)]
        result.append(
            Aggregate(
                case_id=case_id,
                family=first["family"],
                contract_id=first["contract_id"],
                library=library,
                library_version=first["library_version"],
                runtime=first["runtime"],
                median_ns=median,
                minimum_ns=min(
                    parse_integer(row["elapsed_ns"]) for row in group_rows
                ),
                measurements=len(group_rows),
                units=parse_integer(first["units"]),
                analysis_ordinals=parse_integer(
                    first["analysis_ordinals"]
                ),
                assessment_ordinals=parse_integer(
                    first["assessment_ordinals"]
                ),
                fixture_checksum=parse_integer(first["fixture_checksum"]),
                quality_primary=parse_integer(first["quality_primary"]),
                quality_secondary=parse_integer(first["quality_secondary"]),
                relative_to_resample4s=median / baseline,
            )
        )
    return result


def write_csv(path: Path, rows: list[Aggregate]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = list(Aggregate.__dataclass_fields__)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle, fieldnames=fields, lineterminator="\n"
        )
        writer.writeheader()
        for row in rows:
            values = row.__dict__.copy()
            values["relative_to_resample4s"] = (
                f"{row.relative_to_resample4s:.4f}"
            )
            writer.writerow(values)


def write_markdown(
    path: Path,
    profile: str,
    rows: list[Aggregate],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"# Resample4s cross-language benchmark: {profile}",
        "",
        "Each timed cell generates canonical zero-based analysis and assessment "
        "ordinals through the library's public API, materializes them, and "
        "performs one linear reduction. Contract validation runs before timing.",
        "",
        "| Case | Library | Median ms | Relative to Resample4s | "
        "Primary quality | Assessment ordinals |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for row in rows:
        lines.append(
            f"| {row.case_id} | {row.library} {row.library_version} | "
            f"{row.median_ns / 1e6:.3f} | "
            f"{row.relative_to_resample4s:.2f}x | "
            f"{row.quality_primary} | {row.assessment_ordinals} |"
        )
    lines.extend(
        [
            "",
            "A relative value above 1 means that the comparator took longer "
            "than Resample4s in this run. It is not a universal speed claim.",
            "",
            "Interpretation boundaries:",
            "",
            "- Inputs, unit counts, role sizes, and semantic contracts match "
            "within each case. Random assignments need not match.",
            "- Non-bootstrap roles are sorted inside the timed region so every "
            "library returns Resample4s's canonical increasing selections.",
            "- Bootstrap compares unconditional draws with OOB complements. "
            "OOB counts may differ because random streams differ.",
            "- rsample uses its public data-frame split API; splitTools and "
            "scikit-learn expose index-oriented APIs. The distinction is part "
            "of the reported public operation.",
            "- Grouped-stratified runtime must be read with primary quality, "
            "which is the common integer allocation objective; lower is "
            "better.",
            "- JVM warmup occurs in-process. Process startup, dependency "
            "loading, model fitting, scoring, and data-feature copying are "
            "outside the timer.",
            "",
            f"Comparison report generated with Python {platform.python_version()}.",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", nargs="+", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--output-csv", type=Path, required=True)
    parser.add_argument("--output-md", type=Path, required=True)
    args = parser.parse_args()
    raw = load(args.inputs)
    validate(raw)
    summary = aggregate(raw)
    write_csv(args.output_csv, summary)
    write_markdown(args.output_md, args.profile, summary)
    print(
        f"validated {len(raw)} raw rows and wrote "
        f"{len(summary)} comparable aggregates"
    )


if __name__ == "__main__":
    main()
