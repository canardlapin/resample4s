# Tessera cross-language benchmark: standard

Each timed cell generates canonical zero-based analysis and assessment ordinals through the library's public API, materializes them, and performs one linear reduction. Contract validation runs before timing.

| Case | Library | Median ms | Relative to Tessera | Primary quality | Assessment ordinals |
|---|---:|---:|---:|---:|---:|
| bootstrap-50k | tessera 0.1.0-SNAPSHOT | 272.565 | 1.00x | 0 | 1839777 |
| bootstrap-50k | rsample 1.3.2 | 508.759 | 1.87x | 0 | 1839638 |
| grouped-100k | tessera 0.1.0-SNAPSHOT | 11.457 | 1.00x | 0 | 100000 |
| grouped-100k | rsample 1.3.2 | 17381.681 | 1517.18x | 0 | 100000 |
| grouped-100k | scikit-learn 1.7.1 | 30.375 | 2.65x | 0 | 100000 |
| grouped-100k | splitTools 1.0.1 | 142.688 | 12.45x | 524 | 100000 |
| grouped-stratified-20k | tessera 0.1.0-SNAPSHOT | 14.618 | 1.00x | 860 | 20000 |
| grouped-stratified-20k | rsample 1.3.2 | 2896.376 | 198.14x | 71660 | 20000 |
| grouped-stratified-20k | scikit-learn 1.7.1 | 284.062 | 19.43x | 30116860 | 20000 |
| kfold-100k | tessera 0.1.0-SNAPSHOT | 55.255 | 1.00x | 0 | 500000 |
| kfold-100k | rsample 1.3.2 | 472.760 | 8.56x | 0 | 500000 |
| kfold-100k | scikit-learn 1.7.1 | 120.298 | 2.18x | 0 | 500000 |
| kfold-100k | splitTools 1.0.1 | 805.181 | 14.57x | 0 | 500000 |
| loo-2k | tessera 0.1.0-SNAPSHOT | 20.460 | 1.00x | 0 | 2000 |
| loo-2k | rsample 1.3.2 | 272.697 | 13.33x | 0 | 2000 |
| loo-2k | scikit-learn 1.7.1 | 83.631 | 4.09x | 0 | 2000 |
| loo-2k | splitTools 1.0.1 | 127.413 | 6.23x | 0 | 2000 |
| monte-carlo-100k | tessera 0.1.0-SNAPSHOT | 1248.301 | 1.00x | 0 | 2000000 |
| monte-carlo-100k | rsample 1.3.2 | 2178.023 | 1.74x | 0 | 2000000 |
| monte-carlo-100k | scikit-learn 1.7.1 | 301.708 | 0.24x | 0 | 2000000 |
| stratified-100k | tessera 0.1.0-SNAPSHOT | 52.676 | 1.00x | 0 | 500000 |
| stratified-100k | rsample 1.3.2 | 487.633 | 9.26x | 0 | 500000 |
| stratified-100k | scikit-learn 1.7.1 | 145.568 | 2.76x | 0 | 500000 |
| stratified-100k | splitTools 1.0.1 | 854.423 | 16.22x | 5 | 500000 |

A relative value above 1 means that the comparator took longer than Tessera in this run. It is not a universal speed claim.

Interpretation boundaries:

- Inputs, unit counts, role sizes, and semantic contracts match within each case. Random assignments need not match.
- Non-bootstrap roles are sorted inside the timed region so every library returns Tessera's canonical increasing selections.
- Bootstrap compares unconditional draws with OOB complements. OOB counts may differ because random streams differ.
- rsample uses its public data-frame split API; splitTools and scikit-learn expose index-oriented APIs. The distinction is part of the reported public operation.
- Grouped-stratified runtime must be read with primary quality, which is the common integer allocation objective; lower is better.
- JVM warmup occurs in-process. Process startup, dependency loading, model fitting, scoring, and data-feature copying are outside the timer.

Comparison report generated with Python 3.13.11.
