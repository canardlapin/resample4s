#!/usr/bin/env bash
set -euo pipefail

benchmark_root="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repository_root="$(CDPATH= cd -- "${benchmark_root}/.." && pwd)"

profile="${1:-smoke}"
warmup="${2:-1}"
measure="${3:-5}"
output_directory="${4:-${benchmark_root}/results/${profile}-latest}"

if [[ "${output_directory}" != /* ]]; then
  output_directory="${repository_root}/${output_directory}"
fi

mkdir -p "${output_directory}"

scala_output="${output_directory}/scala.csv"
python_output="${output_directory}/python.csv"
r_output="${output_directory}/r.csv"
comparison_output="${output_directory}/comparison.csv"
report_output="${output_directory}/report.md"
environment_output="${output_directory}/environment.txt"

(
  cd "${repository_root}"
  sbt -batch \
    "benchmarks/runMain resample4s.benchmarks.BenchmarkMain --manifest benchmarks/cases.csv --profile ${profile} --warmup ${warmup} --measure ${measure} --output ${scala_output}"
)

(
  cd "${benchmark_root}/python"
  uv sync --frozen
  uv run --frozen python benchmark.py \
    --manifest ../cases.csv \
    --profile "${profile}" \
    --warmup "${warmup}" \
    --measure "${measure}" \
    --output "${python_output}"
)

(
  cd "${benchmark_root}/r"
  R_PROFILE_USER=/dev/null Rscript benchmark.R \
    --manifest ../cases.csv \
    --profile "${profile}" \
    --warmup "${warmup}" \
    --measure "${measure}" \
    --output "${r_output}"
)

(
  cd "${benchmark_root}/python"
  uv run --frozen python compare.py \
    --inputs "${scala_output}" "${python_output}" "${r_output}" \
    --profile "${profile}" \
    --output-csv "${comparison_output}" \
    --output-md "${report_output}"
)

"${benchmark_root}/capture-environment.sh" \
  "${scala_output}" \
  "${python_output}" \
  "${r_output}" \
  "${profile}" \
  "${warmup}" \
  "${measure}" \
  "${environment_output}"

printf '%s\n' "benchmark report: ${report_output}"
