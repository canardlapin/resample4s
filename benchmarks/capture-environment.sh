#!/usr/bin/env bash
set -euo pipefail

scala_output="$1"
python_output="$2"
r_output="$3"
profile="$4"
warmup="$5"
measure="$6"
environment_output="$7"

first_csv_value() {
  local path="$1"
  local column="$2"
  awk -F, -v column="${column}" '
    NR == 2 {
      gsub(/\r/, "", $column)
      print $column
      exit
    }
  ' "${path}"
}

library_csv_value() {
  local path="$1"
  local library="$2"
  local column="$3"
  awk -F, -v library="${library}" -v column="${column}" '
    NR > 1 && $2 == library {
      gsub(/\r/, "", $column)
      print $column
      exit
    }
  ' "${path}"
}

benchmark_root="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

{
  printf 'Machine: %s\n' "$(uname -m)"
  printf 'Operating system: %s\n' "$(uname -srv)"
  printf 'Shell Java: '
  java -version 2>&1 | sed -n '1p'
  printf 'sbt benchmark Java: %s\n' \
    "$(first_csv_value "${scala_output}" 4)"
  printf 'Resample4s: %s\n' \
    "$(first_csv_value "${scala_output}" 3)"
  printf 'Python: %s\n' \
    "$(first_csv_value "${python_output}" 4)"
  printf 'scikit-learn: %s\n' \
    "$(first_csv_value "${python_output}" 3)"
  printf 'R: %s\n' \
    "$(first_csv_value "${r_output}" 4)"
  printf 'rsample: %s\n' \
    "$(library_csv_value "${r_output}" rsample 3)"
  printf 'splitTools: %s\n' \
    "$(library_csv_value "${r_output}" splitTools 3)"
  (
    cd "${benchmark_root}/r"
    LC_ALL=C R_PROFILE_USER=/dev/null Rscript -e \
      'p <- renv::paths$library(project = "."); .libPaths(c(p, .libPaths())); cat("bench clock: ", as.character(packageVersion("bench")), "\n", sep = "")'
  )
  printf 'Profile: %s\n' "${profile}"
  printf 'Warmups per cell: %s\n' "${warmup}"
  printf 'Measurements per cell: %s\n' "${measure}"
} >"${environment_output}" 2>&1
