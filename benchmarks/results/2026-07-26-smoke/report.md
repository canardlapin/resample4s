# Tessera cross-language benchmark: smoke

Each timed cell generates canonical zero-based analysis and assessment ordinals through the library's public API, materializes them, and performs one linear reduction. Contract validation runs before timing.

| Case | Library | Median ms | Relative to Tessera | Primary quality | Assessment ordinals |
|---|---:|---:|---:|---:|---:|
| bootstrap-smoke | tessera 0.1.0-SNAPSHOT | 1.746 | 1.00x | 0 | 1823 |
| bootstrap-smoke | rsample 1.3.2 | 1.470 | 0.84x | 0 | 1808 |
| grouped-smoke | tessera 0.1.0-SNAPSHOT | 0.948 | 1.00x | 0 | 1200 |
| grouped-smoke | rsample 1.3.2 | 164.375 | 173.32x | 5 | 1200 |
| grouped-smoke | scikit-learn 1.7.1 | 0.256 | 0.27x | 0 | 1200 |
| grouped-smoke | splitTools 1.0.1 | 0.290 | 0.31x | 64 | 1200 |
| grouped-stratified-smoke | tessera 0.1.0-SNAPSHOT | 1.291 | 1.00x | 300 | 1200 |
| grouped-stratified-smoke | rsample 1.3.2 | 148.901 | 115.34x | 26950 | 1200 |
| grouped-stratified-smoke | scikit-learn 1.7.1 | 8.258 | 6.40x | 339150 | 1200 |
| kfold-smoke | tessera 0.1.0-SNAPSHOT | 1.834 | 1.00x | 0 | 2000 |
| kfold-smoke | rsample 1.3.2 | 2.505 | 1.37x | 0 | 2000 |
| kfold-smoke | scikit-learn 1.7.1 | 0.328 | 0.18x | 0 | 2000 |
| kfold-smoke | splitTools 1.0.1 | 0.576 | 0.31x | 0 | 2000 |
| loo-smoke | tessera 0.1.0-SNAPSHOT | 0.220 | 1.00x | 0 | 200 |
| loo-smoke | rsample 1.3.2 | 17.885 | 81.30x | 0 | 200 |
| loo-smoke | scikit-learn 1.7.1 | 1.396 | 6.35x | 0 | 200 |
| loo-smoke | splitTools 1.0.1 | 4.171 | 18.96x | 0 | 200 |
| monte-carlo-smoke | tessera 0.1.0-SNAPSHOT | 2.332 | 1.00x | 0 | 2000 |
| monte-carlo-smoke | rsample 1.3.2 | 1.987 | 0.85x | 0 | 2000 |
| monte-carlo-smoke | scikit-learn 1.7.1 | 0.327 | 0.14x | 0 | 2000 |
| stratified-smoke | tessera 0.1.0-SNAPSHOT | 1.599 | 1.00x | 0 | 2400 |
| stratified-smoke | rsample 1.3.2 | 4.306 | 2.69x | 0 | 2400 |
| stratified-smoke | scikit-learn 1.7.1 | 0.634 | 0.40x | 0 | 2400 |
| stratified-smoke | splitTools 1.0.1 | 0.886 | 0.55x | 0 | 2400 |

A relative value above 1 means that the comparator took longer than Tessera in this run. It is not a universal speed claim.

Interpretation boundaries:

- Inputs, unit counts, role sizes, and semantic contracts match within each case. Random assignments need not match.
- Non-bootstrap roles are sorted inside the timed region so every library returns Tessera's canonical increasing selections.
- Bootstrap compares unconditional draws with OOB complements. OOB counts may differ because random streams differ.
- rsample uses its public data-frame split API; splitTools and scikit-learn expose index-oriented APIs. The distinction is part of the reported public operation.
- Grouped-stratified runtime must be read with primary quality, which is the common integer allocation objective; lower is better.
- JVM warmup occurs in-process. Process startup, dependency loading, model fitting, scoring, and data-feature copying are outside the timer.

Comparison report generated with Python 3.13.11.
