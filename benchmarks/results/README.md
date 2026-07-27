# Benchmark results

This directory contains reproducible cross-language benchmark evidence.
Subdirectories produced by `../run.sh` contain:

- `scala.csv`, `python.csv`, and `r.csv`: raw measurements;
- `comparison.csv`: validated per-case medians and Resample4s-relative ratios;
- `report.md`: the human-readable comparison;
- `environment.txt`: operating-system and runtime versions.

Do not compare rows from different machines as if they were one experiment.
Each report states its semantic and measurement boundaries.
