from __future__ import annotations

import argparse
import csv
import platform
import statistics
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Iterator

import numpy as np
import sklearn
from sklearn.model_selection import (
    GroupKFold,
    KFold,
    LeaveOneOut,
    RepeatedKFold,
    RepeatedStratifiedKFold,
    ShuffleSplit,
    StratifiedGroupKFold,
    StratifiedKFold,
)


PROTOCOL = "resample4s-benchmark/v1"
MODULUS = 2_147_483_647
BENCHMARK_SEED = 20_260_726
HEADER = [
    "protocol",
    "library",
    "library_version",
    "runtime",
    "case_id",
    "family",
    "contract_id",
    "n",
    "folds",
    "repeats",
    "measurement",
    "elapsed_ns",
    "units",
    "analysis_ordinals",
    "assessment_ordinals",
    "fixture_checksum",
    "semantic_checksum",
    "contract_ok",
    "quality_primary",
    "quality_secondary",
]


@dataclass(frozen=True)
class BenchmarkCase:
    profile: str
    case_id: str
    family: str
    n: int
    folds: int
    repeats: int
    fraction_num: int
    fraction_den: int
    groups: str
    strata: str

    @property
    def contract_id(self) -> str:
        return {
            "kfold": "exact-partition/v1",
            "stratified": "exact-partition/v1",
            "grouped": "group-exact-partition/v1",
            "grouped_stratified": "group-stratified-exact-partition/v1",
            "monte_carlo": "monte-carlo-complement/v1",
            "bootstrap": "bootstrap-oob/v1",
            "loo": "exact-partition/v1",
        }[self.family]


@dataclass(frozen=True)
class Fixture:
    groups: np.ndarray
    strata: np.ndarray
    group_count: int
    stratum_count: int
    checksum: int


@dataclass(frozen=True)
class Observation:
    units: int
    analysis_ordinals: int
    assessment_ordinals: int
    checksum: int


@dataclass(frozen=True)
class ContractEvidence:
    quality_primary: int
    quality_secondary: int


SplitFactory = Callable[[], Iterator[tuple[np.ndarray, np.ndarray]]]


def read_manifest(path: Path) -> list[BenchmarkCase]:
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    cases: list[BenchmarkCase] = []
    for row in rows:
        if row["protocol"] != PROTOCOL:
            raise ValueError(f"unexpected protocol: {row['protocol']}")
        cases.append(
            BenchmarkCase(
                profile=row["profile"],
                case_id=row["case_id"],
                family=row["family"],
                n=int(row["n"]),
                folds=int(row["folds"]),
                repeats=int(row["repeats"]),
                fraction_num=int(row["fraction_num"]),
                fraction_den=int(row["fraction_den"]),
                groups=row["groups"],
                strata=row["strata"],
            )
        )
    return cases


def build_fixture(case: BenchmarkCase) -> Fixture:
    if case.groups == "none":
        groups = np.full(case.n, -1, dtype=np.int64)
    elif case.groups == "balanced":
        groups = np.arange(case.n, dtype=np.int64) // 8
    elif case.groups == "skewed":
        groups = np.empty(case.n, dtype=np.int64)
        index = 0
        group = 0
        while index < case.n:
            size = 1 + (group * 17) % 23
            end = min(case.n, index + size)
            groups[index:end] = group
            index = end
            group += 1
    else:
        raise ValueError(f"unknown group pattern: {case.groups}")

    if case.strata == "none":
        strata = np.full(case.n, -1, dtype=np.int64)
    elif case.strata == "balanced4":
        strata = np.arange(case.n, dtype=np.int64) % 4
    elif case.strata == "group_balanced4":
        if np.any(groups < 0):
            raise ValueError("group_balanced4 requires groups")
        strata = groups % 4
    else:
        raise ValueError(f"unknown stratum pattern: {case.strata}")

    group_count = 0 if case.groups == "none" else int(groups.max()) + 1
    stratum_count = 0 if case.strata == "none" else int(strata.max()) + 1
    checksum = 17
    for value in groups:
        checksum = (checksum * 31 + int(value) + 2) % MODULUS
    checksum = (checksum * 31 + 97) % MODULUS
    for value in strata:
        checksum = (checksum * 31 + int(value) + 2) % MODULUS
    return Fixture(groups, strata, group_count, stratum_count, checksum)


def split_factory(case: BenchmarkCase, fixture: Fixture) -> SplitFactory | None:
    x = np.zeros(case.n, dtype=np.uint8)

    if case.family == "bootstrap":
        return None
    if case.family == "kfold":
        if case.repeats == 1:
            splitter = KFold(
                n_splits=case.folds,
                shuffle=True,
                random_state=BENCHMARK_SEED,
            )
        else:
            splitter = RepeatedKFold(
                n_splits=case.folds,
                n_repeats=case.repeats,
                random_state=BENCHMARK_SEED,
            )
        return lambda: splitter.split(x)
    if case.family == "stratified":
        if case.repeats == 1:
            splitter = StratifiedKFold(
                n_splits=case.folds,
                shuffle=True,
                random_state=BENCHMARK_SEED,
            )
        else:
            splitter = RepeatedStratifiedKFold(
                n_splits=case.folds,
                n_repeats=case.repeats,
                random_state=BENCHMARK_SEED,
            )
        return lambda: splitter.split(x, fixture.strata)
    if case.family == "grouped":
        if case.repeats != 1:
            return None
        splitter = GroupKFold(n_splits=case.folds)
        return lambda: splitter.split(
            x, groups=fixture.groups
        )
    if case.family == "grouped_stratified":
        if case.repeats != 1:
            return None
        splitter = StratifiedGroupKFold(
            n_splits=case.folds,
            shuffle=True,
            random_state=BENCHMARK_SEED,
        )
        return lambda: splitter.split(x, fixture.strata, fixture.groups)
    if case.family == "monte_carlo":
        splitter = ShuffleSplit(
            n_splits=case.repeats,
            test_size=case.fraction_num / case.fraction_den,
            random_state=BENCHMARK_SEED,
        )
        return lambda: splitter.split(x)
    if case.family == "loo":
        splitter = LeaveOneOut()
        return lambda: splitter.split(x)
    raise ValueError(f"unsupported family: {case.family}")


def materialized(factory: SplitFactory) -> list[tuple[np.ndarray, np.ndarray]]:
    return [
        (
            np.asarray(analysis, dtype=np.int64),
            np.asarray(assessment, dtype=np.int64),
        )
        for analysis, assessment in factory()
    ]


def canonical_factory(factory: SplitFactory) -> SplitFactory:
    def canonical() -> Iterator[tuple[np.ndarray, np.ndarray]]:
        for analysis, assessment in factory():
            yield (
                np.sort(np.asarray(analysis, dtype=np.int64)),
                np.sort(np.asarray(assessment, dtype=np.int64)),
            )

    return canonical


def consume(factory: SplitFactory) -> Observation:
    units = 0
    analysis_ordinals = 0
    assessment_ordinals = 0
    checksum = 17
    for analysis, assessment in factory():
        analysis_sum = int(np.sum(analysis, dtype=np.int64))
        assessment_sum = int(np.sum(assessment, dtype=np.int64))
        checksum = (
            checksum
            + 31 * (analysis_sum % MODULUS)
            + 37 * (assessment_sum % MODULUS)
            + 41 * len(analysis)
            + 43 * len(assessment)
        ) % MODULUS
        units += 1
        analysis_ordinals += len(analysis)
        assessment_ordinals += len(assessment)
    return Observation(
        units, analysis_ordinals, assessment_ordinals, checksum
    )


def validate(
    case: BenchmarkCase,
    fixture: Fixture,
    splits: list[tuple[np.ndarray, np.ndarray]],
) -> ContractEvidence:
    expected_units = {
        "kfold": case.folds * case.repeats,
        "stratified": case.folds * case.repeats,
        "grouped": case.folds * case.repeats,
        "grouped_stratified": case.folds * case.repeats,
        "monte_carlo": case.repeats,
        "loo": case.n,
    }[case.family]
    if len(splits) != expected_units:
        raise AssertionError(
            f"expected {expected_units} units, observed {len(splits)}"
        )
    if case.family == "monte_carlo":
        expected_assessment = case.n * case.fraction_num // case.fraction_den
        for unit, (analysis, assessment) in enumerate(splits):
            validate_partition(case.n, analysis, assessment)
            if len(assessment) != expected_assessment:
                raise AssertionError(
                    f"unit {unit} assessment size {len(assessment)}, "
                    f"expected {expected_assessment}"
                )
        return ContractEvidence(0, 0)
    return validate_exact(case, fixture, splits)


def validate_partition(
    n: int, analysis: np.ndarray, assessment: np.ndarray
) -> None:
    if len(analysis) + len(assessment) != n:
        raise AssertionError("analysis and assessment do not cover n rows")
    for role, values in (("analysis", analysis), ("assessment", assessment)):
        if np.any(values < 0) or np.any(values >= n):
            raise AssertionError(f"{role} contains an out-of-range ordinal")
        if len(values) > 1 and np.any(np.diff(values) <= 0):
            raise AssertionError(f"{role} is not strictly increasing")
    if np.intersect1d(analysis, assessment, assume_unique=True).size:
        raise AssertionError("analysis and assessment overlap")


def validate_exact(
    case: BenchmarkCase,
    fixture: Fixture,
    splits: list[tuple[np.ndarray, np.ndarray]],
) -> ContractEvidence:
    repeats = 1 if case.family == "loo" else case.repeats
    folds = case.n if case.family == "loo" else case.folds
    coverage = np.zeros((repeats, case.n), dtype=np.int16)
    fold_sizes = np.zeros((repeats, folds), dtype=np.int64)
    strata_counts = np.zeros(
        (repeats, folds, fixture.stratum_count), dtype=np.int64
    )
    group_fold = np.full(
        (repeats, fixture.group_count), -1, dtype=np.int64
    )
    for unit, (analysis, assessment) in enumerate(splits):
        repeat, fold = divmod(unit, folds)
        validate_partition(case.n, analysis, assessment)
        coverage[repeat, assessment] += 1
        fold_sizes[repeat, fold] = len(assessment)
        if fixture.stratum_count:
            strata_counts[repeat, fold] = np.bincount(
                fixture.strata[assessment],
                minlength=fixture.stratum_count,
            )
        if fixture.group_count:
            for row in assessment:
                group = fixture.groups[row]
                previous = group_fold[repeat, group]
                if previous == -1:
                    group_fold[repeat, group] = fold
                elif previous != fold:
                    raise AssertionError(f"group {group} crosses folds")
    if np.any(coverage != 1):
        raise AssertionError("assessment folds are not an exact partition")

    fold_imbalance = int(
        max(
            int(values.max()) - int(values.min())
            for values in fold_sizes
        )
    )
    stratum_deviation = 0
    if fixture.stratum_count:
        for repeat in range(repeats):
            for stratum in range(fixture.stratum_count):
                values = strata_counts[repeat, :, stratum]
                stratum_deviation = max(
                    stratum_deviation,
                    int(values.max()) - int(values.min()),
                )

    if case.family == "stratified":
        primary = stratum_deviation
    elif case.family == "grouped":
        primary = fold_imbalance
    elif case.family == "grouped_stratified":
        totals = np.bincount(
            fixture.strata, minlength=fixture.stratum_count
        )
        objectives: list[int] = []
        for repeat in range(repeats):
            objective = 0
            for fold in range(folds):
                for stratum in range(fixture.stratum_count):
                    delta = (
                        folds * int(strata_counts[repeat, fold, stratum])
                        - int(totals[stratum])
                    )
                    objective += delta * delta
                size_delta = folds * int(fold_sizes[repeat, fold]) - case.n
                objective += size_delta * size_delta
            objectives.append(objective)
        primary = max(objectives, default=0)
    else:
        primary = 0
    return ContractEvidence(primary, fold_imbalance)


def benchmark_case(
    case: BenchmarkCase,
    fixture: Fixture,
    factory: SplitFactory,
    warmup: int,
    measure: int,
) -> list[dict[str, object]]:
    evidence = validate(case, fixture, materialized(factory))
    blackhole = 0
    for _ in range(warmup):
        blackhole ^= consume(factory).checksum
    rows: list[dict[str, object]] = []
    for measurement in range(measure):
        start = time.perf_counter_ns()
        observation = consume(factory)
        elapsed = time.perf_counter_ns() - start
        blackhole ^= observation.checksum
        rows.append(
            {
                "protocol": PROTOCOL,
                "library": "scikit-learn",
                "library_version": sklearn.__version__,
                "runtime": platform.python_version(),
                "case_id": case.case_id,
                "family": case.family,
                "contract_id": case.contract_id,
                "n": case.n,
                "folds": case.folds,
                "repeats": case.repeats,
                "measurement": measurement,
                "elapsed_ns": elapsed,
                "units": observation.units,
                "analysis_ordinals": observation.analysis_ordinals,
                "assessment_ordinals": observation.assessment_ordinals,
                "fixture_checksum": fixture.checksum,
                "semantic_checksum": observation.checksum,
                "contract_ok": "true",
                "quality_primary": evidence.quality_primary,
                "quality_secondary": evidence.quality_secondary,
            }
        )
    if blackhole == -1:
        raise AssertionError("unreachable anti-dead-code guard")
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--warmup", required=True, type=int)
    parser.add_argument("--measure", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    cases = [
        case
        for case in read_manifest(args.manifest)
        if case.profile == args.profile
    ]
    if not cases:
        raise ValueError(f"profile {args.profile} has no cases")
    rows: list[dict[str, object]] = []
    skipped: list[str] = []
    for case in cases:
        fixture = build_fixture(case)
        factory = split_factory(case, fixture)
        if factory is None:
            skipped.append(case.case_id)
            continue
        factory = canonical_factory(factory)
        rows.extend(
            benchmark_case(
                case, fixture, factory, args.warmup, args.measure
            )
        )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle, fieldnames=HEADER, lineterminator="\n"
        )
        writer.writeheader()
        writer.writerows(rows)
    medians = {
        case_id: statistics.median(
            int(row["elapsed_ns"])
            for row in rows
            if row["case_id"] == case_id
        )
        for case_id in {str(row["case_id"]) for row in rows}
    }
    print(
        f"wrote {len(rows)} scikit-learn measurements for "
        f"{len(medians)} cases to {args.output}; skipped={skipped}"
    )


if __name__ == "__main__":
    main()
