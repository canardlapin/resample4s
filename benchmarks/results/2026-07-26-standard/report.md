# Resample4s cross-language benchmark: standard

Each timed cell generates canonical zero-based analysis and assessment ordinals through the library's public API, materializes them, and performs one linear reduction. Contract validation runs before timing.

| Case | Library | Median ms | Relative to Resample4s | Primary quality | Assessment ordinals |
|---|---:|---:|---:|---:|---:|
| bootstrap-50k | resample4s 0.1.0-SNAPSHOT | 110.390 | 1.00x | 0 | 1840335 |
| bootstrap-50k | rsample 1.3.2 | 508.164 | 4.60x | 0 | 1839638 |
| grouped-100k | resample4s 0.1.0-SNAPSHOT | 11.180 | 1.00x | 0 | 100000 |
| grouped-100k | rsample 1.3.2 | 16839.486 | 1506.17x | 0 | 100000 |
| grouped-100k | scikit-learn 1.7.1 | 30.795 | 2.75x | 0 | 100000 |
| grouped-100k | splitTools 1.0.1 | 140.219 | 12.54x | 524 | 100000 |
| grouped-stratified-20k | resample4s 0.1.0-SNAPSHOT | 17.837 | 1.00x | 660 | 20000 |
| grouped-stratified-20k | rsample 1.3.2 | 2765.983 | 155.07x | 71660 | 20000 |
| grouped-stratified-20k | scikit-learn 1.7.1 | 287.414 | 16.11x | 30116860 | 20000 |
| kfold-100k | resample4s 0.1.0-SNAPSHOT | 36.088 | 1.00x | 0 | 500000 |
| kfold-100k | rsample 1.3.2 | 460.096 | 12.75x | 0 | 500000 |
| kfold-100k | scikit-learn 1.7.1 | 121.032 | 3.35x | 0 | 500000 |
| kfold-100k | splitTools 1.0.1 | 790.989 | 21.92x | 0 | 500000 |
| loo-2k | resample4s 0.1.0-SNAPSHOT | 12.629 | 1.00x | 0 | 2000 |
| loo-2k | rsample 1.3.2 | 271.333 | 21.48x | 0 | 2000 |
| loo-2k | scikit-learn 1.7.1 | 68.427 | 5.42x | 0 | 2000 |
| loo-2k | splitTools 1.0.1 | 122.783 | 9.72x | 0 | 2000 |
| monte-carlo-100k | resample4s 0.1.0-SNAPSHOT | 94.025 | 1.00x | 0 | 2000000 |
| monte-carlo-100k | rsample 1.3.2 | 2178.829 | 23.17x | 0 | 2000000 |
| monte-carlo-100k | scikit-learn 1.7.1 | 290.519 | 3.09x | 0 | 2000000 |
| stratified-100k | resample4s 0.1.0-SNAPSHOT | 41.455 | 1.00x | 0 | 500000 |
| stratified-100k | rsample 1.3.2 | 502.087 | 12.11x | 0 | 500000 |
| stratified-100k | scikit-learn 1.7.1 | 147.043 | 3.55x | 0 | 500000 |
| stratified-100k | splitTools 1.0.1 | 819.408 | 19.77x | 5 | 500000 |

A relative value above 1 means that the comparator took longer than Resample4s in this run. It is not a universal speed claim.

Interpretation boundaries:

- Inputs, unit counts, role sizes, and semantic contracts match within each case. Random assignments need not match.
- Non-bootstrap roles are sorted inside the timed region so every library returns Resample4s's canonical increasing selections.
- Bootstrap compares unconditional draws with OOB complements. OOB counts may differ because random streams differ.
- rsample uses its public data-frame split API; splitTools and scikit-learn expose index-oriented APIs. The distinction is part of the reported public operation.
- Grouped-stratified runtime must be read with primary quality, which is the common integer allocation objective; lower is better.
- JVM warmup occurs in-process. Process startup, dependency loading, model fitting, scoring, and data-feature copying are outside the timer.

Comparison report generated with Python 3.13.11.
