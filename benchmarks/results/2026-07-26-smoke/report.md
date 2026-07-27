# Resample4s cross-language benchmark: smoke

Each timed cell generates canonical zero-based analysis and assessment ordinals through the library's public API, materializes them, and performs one linear reduction. Contract validation runs before timing.

| Case | Library | Median ms | Relative to Resample4s | Primary quality | Assessment ordinals |
|---|---:|---:|---:|---:|---:|
| bootstrap-smoke | resample4s 0.1.0-SNAPSHOT | 1.891 | 1.00x | 0 | 1824 |
| bootstrap-smoke | rsample 1.3.2 | 1.507 | 0.80x | 0 | 1808 |
| grouped-smoke | resample4s 0.1.0-SNAPSHOT | 0.943 | 1.00x | 0 | 1200 |
| grouped-smoke | rsample 1.3.2 | 173.337 | 183.77x | 5 | 1200 |
| grouped-smoke | scikit-learn 1.7.1 | 0.248 | 0.26x | 0 | 1200 |
| grouped-smoke | splitTools 1.0.1 | 0.266 | 0.28x | 64 | 1200 |
| grouped-stratified-smoke | resample4s 0.1.0-SNAPSHOT | 1.824 | 1.00x | 700 | 1200 |
| grouped-stratified-smoke | rsample 1.3.2 | 148.516 | 81.42x | 26950 | 1200 |
| grouped-stratified-smoke | scikit-learn 1.7.1 | 8.229 | 4.51x | 339150 | 1200 |
| kfold-smoke | resample4s 0.1.0-SNAPSHOT | 1.970 | 1.00x | 0 | 2000 |
| kfold-smoke | rsample 1.3.2 | 2.993 | 1.52x | 0 | 2000 |
| kfold-smoke | scikit-learn 1.7.1 | 0.339 | 0.17x | 0 | 2000 |
| kfold-smoke | splitTools 1.0.1 | 0.535 | 0.27x | 0 | 2000 |
| loo-smoke | resample4s 0.1.0-SNAPSHOT | 2.138 | 1.00x | 0 | 200 |
| loo-smoke | rsample 1.3.2 | 18.900 | 8.84x | 0 | 200 |
| loo-smoke | scikit-learn 1.7.1 | 1.382 | 0.65x | 0 | 200 |
| loo-smoke | splitTools 1.0.1 | 4.023 | 1.88x | 0 | 200 |
| monte-carlo-smoke | resample4s 0.1.0-SNAPSHOT | 0.473 | 1.00x | 0 | 2000 |
| monte-carlo-smoke | rsample 1.3.2 | 1.902 | 4.02x | 0 | 2000 |
| monte-carlo-smoke | scikit-learn 1.7.1 | 0.313 | 0.66x | 0 | 2000 |
| stratified-smoke | resample4s 0.1.0-SNAPSHOT | 1.865 | 1.00x | 0 | 2400 |
| stratified-smoke | rsample 1.3.2 | 4.324 | 2.32x | 0 | 2400 |
| stratified-smoke | scikit-learn 1.7.1 | 0.647 | 0.35x | 0 | 2400 |
| stratified-smoke | splitTools 1.0.1 | 0.921 | 0.49x | 0 | 2400 |

A relative value above 1 means that the comparator took longer than Resample4s in this run. It is not a universal speed claim.

Interpretation boundaries:

- Inputs, unit counts, role sizes, and semantic contracts match within each case. Random assignments need not match.
- Non-bootstrap roles are sorted inside the timed region so every library returns Resample4s's canonical increasing selections.
- Bootstrap compares unconditional draws with OOB complements. OOB counts may differ because random streams differ.
- rsample uses its public data-frame split API; splitTools and scikit-learn expose index-oriented APIs. The distinction is part of the reported public operation.
- Grouped-stratified runtime must be read with primary quality, which is the common integer allocation objective; lower is better.
- JVM warmup occurs in-process. Process startup, dependency loading, model fitting, scoring, and data-feature copying are outside the timer.

Comparison report generated with Python 3.13.11.
