# Tessera cross-language benchmark: smoke

Each timed cell generates canonical zero-based analysis and assessment ordinals through the library's public API, materializes them, and performs one linear reduction. Contract validation runs before timing.

| Case | Library | Median ms | Relative to Tessera | Primary quality | Assessment ordinals |
|---|---:|---:|---:|---:|---:|
| bootstrap-smoke | tessera 0.1.0-SNAPSHOT | 1.588 | 1.00x | 0 | 1823 |
| bootstrap-smoke | rsample 1.3.2 | 1.577 | 0.99x | 0 | 1808 |
| grouped-smoke | tessera 0.1.0-SNAPSHOT | 0.952 | 1.00x | 0 | 1200 |
| grouped-smoke | rsample 1.3.2 | 172.549 | 181.20x | 5 | 1200 |
| grouped-smoke | scikit-learn 1.7.1 | 0.282 | 0.30x | 0 | 1200 |
| grouped-smoke | splitTools 1.0.1 | 0.301 | 0.32x | 64 | 1200 |
| grouped-stratified-smoke | tessera 0.1.0-SNAPSHOT | 1.301 | 1.00x | 300 | 1200 |
| grouped-stratified-smoke | rsample 1.3.2 | 147.977 | 113.71x | 26950 | 1200 |
| grouped-stratified-smoke | scikit-learn 1.7.1 | 8.041 | 6.18x | 339150 | 1200 |
| kfold-smoke | tessera 0.1.0-SNAPSHOT | 1.684 | 1.00x | 0 | 2000 |
| kfold-smoke | rsample 1.3.2 | 2.787 | 1.65x | 0 | 2000 |
| kfold-smoke | scikit-learn 1.7.1 | 0.316 | 0.19x | 0 | 2000 |
| kfold-smoke | splitTools 1.0.1 | 0.529 | 0.31x | 0 | 2000 |
| loo-smoke | tessera 0.1.0-SNAPSHOT | 0.223 | 1.00x | 0 | 200 |
| loo-smoke | rsample 1.3.2 | 18.339 | 82.13x | 0 | 200 |
| loo-smoke | scikit-learn 1.7.1 | 1.503 | 6.73x | 0 | 200 |
| loo-smoke | splitTools 1.0.1 | 4.298 | 19.25x | 0 | 200 |
| monte-carlo-smoke | tessera 0.1.0-SNAPSHOT | 0.704 | 1.00x | 0 | 2000 |
| monte-carlo-smoke | rsample 1.3.2 | 2.045 | 2.91x | 0 | 2000 |
| monte-carlo-smoke | scikit-learn 1.7.1 | 0.307 | 0.44x | 0 | 2000 |
| stratified-smoke | tessera 0.1.0-SNAPSHOT | 1.594 | 1.00x | 0 | 2400 |
| stratified-smoke | rsample 1.3.2 | 4.329 | 2.72x | 0 | 2400 |
| stratified-smoke | scikit-learn 1.7.1 | 0.627 | 0.39x | 0 | 2400 |
| stratified-smoke | splitTools 1.0.1 | 0.858 | 0.54x | 0 | 2400 |

A relative value above 1 means that the comparator took longer than Tessera in this run. It is not a universal speed claim.

Interpretation boundaries:

- Inputs, unit counts, role sizes, and semantic contracts match within each case. Random assignments need not match.
- Non-bootstrap roles are sorted inside the timed region so every library returns Tessera's canonical increasing selections.
- Bootstrap compares unconditional draws with OOB complements. OOB counts may differ because random streams differ.
- rsample uses its public data-frame split API; splitTools and scikit-learn expose index-oriented APIs. The distinction is part of the reported public operation.
- Grouped-stratified runtime must be read with primary quality, which is the common integer allocation objective; lower is better.
- JVM warmup occurs in-process. Process startup, dependency loading, model fitting, scoring, and data-feature copying are outside the timer.

Comparison report generated with Python 3.13.11.
