# Tessera cross-language benchmark: standard

Each timed cell generates canonical zero-based analysis and assessment ordinals through the library's public API, materializes them, and performs one linear reduction. Contract validation runs before timing.

| Case | Library | Median ms | Relative to Tessera | Primary quality | Assessment ordinals |
|---|---:|---:|---:|---:|---:|
| bootstrap-50k | tessera 0.1.0-SNAPSHOT | 105.381 | 1.00x | 0 | 1839777 |
| bootstrap-50k | rsample 1.3.2 | 514.558 | 4.88x | 0 | 1839638 |
| grouped-100k | tessera 0.1.0-SNAPSHOT | 10.238 | 1.00x | 0 | 100000 |
| grouped-100k | rsample 1.3.2 | 16369.776 | 1598.85x | 0 | 100000 |
| grouped-100k | scikit-learn 1.7.1 | 29.417 | 2.87x | 0 | 100000 |
| grouped-100k | splitTools 1.0.1 | 139.331 | 13.61x | 524 | 100000 |
| grouped-stratified-20k | tessera 0.1.0-SNAPSHOT | 17.375 | 1.00x | 860 | 20000 |
| grouped-stratified-20k | rsample 1.3.2 | 2926.173 | 168.42x | 71660 | 20000 |
| grouped-stratified-20k | scikit-learn 1.7.1 | 274.186 | 15.78x | 30116860 | 20000 |
| kfold-100k | tessera 0.1.0-SNAPSHOT | 31.379 | 1.00x | 0 | 500000 |
| kfold-100k | rsample 1.3.2 | 441.437 | 14.07x | 0 | 500000 |
| kfold-100k | scikit-learn 1.7.1 | 116.738 | 3.72x | 0 | 500000 |
| kfold-100k | splitTools 1.0.1 | 762.771 | 24.31x | 0 | 500000 |
| loo-2k | tessera 0.1.0-SNAPSHOT | 21.129 | 1.00x | 0 | 2000 |
| loo-2k | rsample 1.3.2 | 271.477 | 12.85x | 0 | 2000 |
| loo-2k | scikit-learn 1.7.1 | 67.546 | 3.20x | 0 | 2000 |
| loo-2k | splitTools 1.0.1 | 121.825 | 5.77x | 0 | 2000 |
| monte-carlo-100k | tessera 0.1.0-SNAPSHOT | 82.417 | 1.00x | 0 | 2000000 |
| monte-carlo-100k | rsample 1.3.2 | 2198.725 | 26.68x | 0 | 2000000 |
| monte-carlo-100k | scikit-learn 1.7.1 | 272.065 | 3.30x | 0 | 2000000 |
| stratified-100k | tessera 0.1.0-SNAPSHOT | 36.221 | 1.00x | 0 | 500000 |
| stratified-100k | rsample 1.3.2 | 472.794 | 13.05x | 0 | 500000 |
| stratified-100k | scikit-learn 1.7.1 | 138.627 | 3.83x | 0 | 500000 |
| stratified-100k | splitTools 1.0.1 | 797.210 | 22.01x | 5 | 500000 |

A relative value above 1 means that the comparator took longer than Tessera in this run. It is not a universal speed claim.

Interpretation boundaries:

- Inputs, unit counts, role sizes, and semantic contracts match within each case. Random assignments need not match.
- Non-bootstrap roles are sorted inside the timed region so every library returns Tessera's canonical increasing selections.
- Bootstrap compares unconditional draws with OOB complements. OOB counts may differ because random streams differ.
- rsample uses its public data-frame split API; splitTools and scikit-learn expose index-oriented APIs. The distinction is part of the reported public operation.
- Grouped-stratified runtime must be read with primary quality, which is the common integer allocation objective; lower is better.
- JVM warmup occurs in-process. Process startup, dependency loading, model fitting, scoring, and data-feature copying are outside the timer.

Comparison report generated with Python 3.13.11.
