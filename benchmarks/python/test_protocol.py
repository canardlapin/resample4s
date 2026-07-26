from __future__ import annotations

import unittest
from pathlib import Path

from benchmark import (
    build_fixture,
    canonical_factory,
    materialized,
    read_manifest,
    split_factory,
    validate,
)
from compare import load, validate as validate_comparison


ROOT = Path(__file__).resolve().parents[1]


class ProtocolTest(unittest.TestCase):
    def test_fixture_checksum_matches_scala_lock(self) -> None:
        case = next(
            case
            for case in read_manifest(ROOT / "cases.csv")
            if case.case_id == "grouped-stratified-smoke"
        )
        self.assertEqual(build_fixture(case).checksum, 128_298_438)

    def test_supported_smoke_cases_satisfy_contracts(self) -> None:
        cases = [
            case
            for case in read_manifest(ROOT / "cases.csv")
            if case.profile == "smoke"
        ]
        supported = 0
        for case in cases:
            fixture = build_fixture(case)
            factory = split_factory(case, fixture)
            if factory is None:
                continue
            factory = canonical_factory(factory)
            evidence = validate(case, fixture, materialized(factory))
            self.assertGreaterEqual(evidence.quality_primary, 0)
            supported += 1
        self.assertEqual(supported, 6)

    def test_smoke_outputs_are_semantically_comparable(self) -> None:
        evidence = ROOT / "results" / "2026-07-26-smoke"
        paths = [
            evidence / "scala.csv",
            evidence / "python.csv",
            evidence / "r.csv",
        ]
        if not all(path.exists() for path in paths):
            self.skipTest("smoke result files have not been generated")
        validate_comparison(load(paths))

    def test_comparison_rejects_mismatched_case_metadata(self) -> None:
        evidence = ROOT / "results" / "2026-07-26-smoke"
        paths = [
            evidence / "scala.csv",
            evidence / "python.csv",
            evidence / "r.csv",
        ]
        if not all(path.exists() for path in paths):
            self.skipTest("smoke result files have not been generated")
        rows = [row.copy() for row in load(paths)]
        target = next(row for row in rows if row["library"] != "tessera")
        target["n"] = str(int(target["n"]) + 1)
        with self.assertRaisesRegex(ValueError, "disagree on n"):
            validate_comparison(rows)

    def test_comparison_rejects_unstable_artifact_sizes(self) -> None:
        evidence = ROOT / "results" / "2026-07-26-smoke"
        paths = [
            evidence / "scala.csv",
            evidence / "python.csv",
            evidence / "r.csv",
        ]
        if not all(path.exists() for path in paths):
            self.skipTest("smoke result files have not been generated")
        rows = [row.copy() for row in load(paths)]
        baseline = rows[0]
        target = next(
            row
            for row in rows
            if row["library"] == baseline["library"]
            and row["case_id"] == baseline["case_id"]
            and row["measurement"] != baseline["measurement"]
        )
        target["units"] = str(int(target["units"]) + 1)
        with self.assertRaisesRegex(ValueError, "unstable units"):
            validate_comparison(rows)


if __name__ == "__main__":
    unittest.main()
