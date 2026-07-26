protocol <- "tessera-benchmark/v1"
modulus <- 2147483647
benchmark_seed <- 20260726L

file_argument <- grep(
  "^--file=",
  commandArgs(trailingOnly = FALSE),
  value = TRUE
)
project_directory <- dirname(normalizePath(
  sub("^--file=", "", file_argument[[1L]]),
  mustWork = TRUE
))
if (requireNamespace("renv", quietly = TRUE)) {
  project_library <- renv::paths$library(project = project_directory)
  .libPaths(c(project_library, .libPaths()))
}

header <- c(
  "protocol", "library", "library_version", "runtime", "case_id",
  "family", "contract_id", "n", "folds", "repeats", "measurement",
  "elapsed_ns", "units", "analysis_ordinals", "assessment_ordinals",
  "fixture_checksum", "semantic_checksum", "contract_ok",
  "quality_primary", "quality_secondary"
)

get_option <- function(args, name) {
  index <- match(name, args)
  if (is.na(index) || index == length(args)) {
    stop("missing required option ", name, call. = FALSE)
  }
  args[[index + 1L]]
}

contract_id <- function(family) {
  switch(
    family,
    kfold = "exact-partition/v1",
    stratified = "exact-partition/v1",
    grouped = "group-exact-partition/v1",
    grouped_stratified = "group-stratified-exact-partition/v1",
    monte_carlo = "monte-carlo-complement/v1",
    bootstrap = "bootstrap-oob/v1",
    loo = "exact-partition/v1",
    stop("unknown family: ", family, call. = FALSE)
  )
}

read_manifest <- function(path, profile) {
  cases <- utils::read.csv(path, stringsAsFactors = FALSE, check.names = FALSE)
  if (!identical(
    names(cases),
    c(
      "protocol", "profile", "case_id", "family", "n", "folds",
      "repeats", "fraction_num", "fraction_den", "groups", "strata"
    )
  )) {
    stop("unexpected manifest header", call. = FALSE)
  }
  if (any(cases$protocol != protocol)) {
    stop("unexpected manifest protocol", call. = FALSE)
  }
  selected <- cases[cases$profile == profile, , drop = FALSE]
  if (nrow(selected) == 0L) {
    stop("profile has no cases: ", profile, call. = FALSE)
  }
  selected
}

build_fixture <- function(case) {
  n <- case$n
  if (case$groups == "none") {
    groups <- rep.int(-1L, n)
  } else if (case$groups == "balanced") {
    groups <- (seq_len(n) - 1L) %/% 8L
  } else if (case$groups == "skewed") {
    groups <- integer(n)
    index <- 1L
    group <- 0L
    while (index <= n) {
      size <- 1L + (group * 17L) %% 23L
      end <- min(n, index + size - 1L)
      groups[index:end] <- group
      index <- end + 1L
      group <- group + 1L
    }
  } else {
    stop("unknown group pattern: ", case$groups, call. = FALSE)
  }

  if (case$strata == "none") {
    strata <- rep.int(-1L, n)
  } else if (case$strata == "balanced4") {
    strata <- (seq_len(n) - 1L) %% 4L
  } else if (case$strata == "group_balanced4") {
    if (any(groups < 0L)) {
      stop("group_balanced4 requires groups", call. = FALSE)
    }
    strata <- groups %% 4L
  } else {
    stop("unknown stratum pattern: ", case$strata, call. = FALSE)
  }

  checksum <- 17
  for (value in groups) {
    checksum <- (checksum * 31 + value + 2) %% modulus
  }
  checksum <- (checksum * 31 + 97) %% modulus
  for (value in strata) {
    checksum <- (checksum * 31 + value + 2) %% modulus
  }
  list(
    groups = groups,
    strata = strata,
    group_count = if (case$groups == "none") 0L else max(groups) + 1L,
    stratum_count = if (case$strata == "none") 0L else max(strata) + 1L,
    checksum = checksum
  )
}

rsample_factory <- function(case, fixture) {
  data <- data.frame(
    .row = seq_len(case$n) - 1L,
    group = fixture$groups,
    stratum = factor(fixture$strata)
  )
  function() {
    set.seed(benchmark_seed)
    resamples <- switch(
      case$family,
      kfold = rsample::vfold_cv(
        data, v = case$folds, repeats = case$repeats
      ),
      stratified = rsample::vfold_cv(
        data,
        v = case$folds,
        repeats = case$repeats,
        strata = stratum
      ),
      grouped = rsample::group_vfold_cv(
        data,
        group = group,
        v = case$folds,
        repeats = case$repeats,
        balance = "observations"
      ),
      grouped_stratified = rsample::group_vfold_cv(
        data,
        group = group,
        v = case$folds,
        repeats = case$repeats,
        balance = "observations",
        strata = stratum
      ),
      monte_carlo = rsample::mc_cv(
        data,
        prop = 1 - case$fraction_num / case$fraction_den,
        times = case$repeats
      ),
      bootstrap = rsample::bootstraps(data, times = case$repeats),
      loo = rsample::loo_cv(data),
      stop("unsupported rsample family: ", case$family, call. = FALSE)
    )
    lapply(resamples$splits, function(split) {
      analysis <- rsample::analysis(split)$.row
      assessment <- rsample::assessment(split)$.row
      if (case$family != "bootstrap") {
        analysis <- sort(analysis)
      }
      list(
        analysis = as.integer(analysis),
        assessment = sort(as.integer(assessment))
      )
    })
  }
}

splittools_factory <- function(case, fixture) {
  if (!case$family %in% c("kfold", "stratified", "grouped", "loo")) {
    return(NULL)
  }
  type <- switch(
    case$family,
    kfold = "basic",
    stratified = "stratified",
    grouped = "grouped",
    loo = "basic"
  )
  y <- switch(
    case$family,
    stratified = fixture$strata,
    grouped = fixture$groups,
    seq_len(case$n)
  )
  k <- if (case$family == "loo") case$n else case$folds
  repeats <- if (case$family == "loo") 1L else case$repeats
  population <- seq_len(case$n)
  function() {
    analysis_sets <- splitTools::create_folds(
      y,
      k = k,
      type = type,
      m_rep = repeats,
      use_names = FALSE,
      invert = FALSE,
      shuffle = FALSE,
      seed = benchmark_seed
    )
    lapply(analysis_sets, function(analysis) {
      analysis <- sort(as.integer(analysis))
      assessment <- setdiff(population, analysis)
      list(
        analysis = analysis - 1L,
        assessment = as.integer(assessment) - 1L
      )
    })
  }
}

consume <- function(factory) {
  splits <- factory()
  analysis_ordinals <- 0
  assessment_ordinals <- 0
  checksum <- 17
  for (split in splits) {
    analysis_sum <- sum(split$analysis)
    assessment_sum <- sum(split$assessment)
    checksum <- (
      checksum +
        31 * (analysis_sum %% modulus) +
        37 * (assessment_sum %% modulus) +
        41 * length(split$analysis) +
        43 * length(split$assessment)
    ) %% modulus
    analysis_ordinals <- analysis_ordinals + length(split$analysis)
    assessment_ordinals <- assessment_ordinals + length(split$assessment)
  }
  list(
    units = length(splits),
    analysis_ordinals = analysis_ordinals,
    assessment_ordinals = assessment_ordinals,
    checksum = checksum
  )
}

validate_partition <- function(n, analysis, assessment) {
  if (length(analysis) + length(assessment) != n) {
    stop("analysis and assessment do not cover n rows", call. = FALSE)
  }
  for (values in list(analysis = analysis, assessment = assessment)) {
    if (any(values < 0L) || any(values >= n)) {
      stop("partition contains an out-of-range ordinal", call. = FALSE)
    }
    if (length(values) > 1L && any(diff(values) <= 0L)) {
      stop("partition role is not strictly increasing", call. = FALSE)
    }
  }
  if (length(intersect(analysis, assessment)) != 0L) {
    stop("analysis and assessment overlap", call. = FALSE)
  }
}

validate_bootstrap <- function(case, splits) {
  for (split in splits) {
    if (length(split$analysis) != case$n) {
      stop("bootstrap draw length differs from n", call. = FALSE)
    }
    if (any(split$analysis < 0L) || any(split$analysis >= case$n)) {
      stop("bootstrap draw contains out-of-range ordinal", call. = FALSE)
    }
    expected <- setdiff(seq_len(case$n) - 1L, unique(split$analysis))
    if (!identical(as.integer(expected), as.integer(split$assessment))) {
      stop("bootstrap assessment is not the OOB complement", call. = FALSE)
    }
  }
  list(quality_primary = 0, quality_secondary = 0)
}

validate_monte_carlo <- function(case, splits) {
  expected <- (case$n * case$fraction_num) %/% case$fraction_den
  for (split in splits) {
    validate_partition(case$n, split$analysis, split$assessment)
    if (length(split$assessment) != expected) {
      stop(
        "Monte Carlo assessment size ", length(split$assessment),
        " differs from expected ", expected,
        call. = FALSE
      )
    }
  }
  list(quality_primary = 0, quality_secondary = 0)
}

validate_exact <- function(case, fixture, splits) {
  repeats <- if (case$family == "loo") 1L else case$repeats
  folds <- if (case$family == "loo") case$n else case$folds
  coverage <- matrix(0L, nrow = repeats, ncol = case$n)
  fold_sizes <- matrix(0L, nrow = repeats, ncol = folds)
  strata_counts <- array(
    0L,
    dim = c(repeats, folds, fixture$stratum_count)
  )
  group_fold <- matrix(
    -1L,
    nrow = repeats,
    ncol = fixture$group_count
  )

  for (unit_index in seq_along(splits)) {
    repeat_index <- (unit_index - 1L) %/% folds + 1L
    fold_index <- (unit_index - 1L) %% folds + 1L
    split <- splits[[unit_index]]
    validate_partition(case$n, split$analysis, split$assessment)
    assessment_columns <- split$assessment + 1L
    coverage[repeat_index, assessment_columns] <-
      coverage[repeat_index, assessment_columns] + 1L
    fold_sizes[repeat_index, fold_index] <- length(split$assessment)

    if (fixture$stratum_count > 0L) {
      counts <- tabulate(
        fixture$strata[assessment_columns] + 1L,
        nbins = fixture$stratum_count
      )
      strata_counts[repeat_index, fold_index, ] <- counts
    }
    if (fixture$group_count > 0L) {
      for (row in split$assessment) {
        group <- fixture$groups[[row + 1L]] + 1L
        previous <- group_fold[repeat_index, group]
        if (previous == -1L) {
          group_fold[repeat_index, group] <- fold_index
        } else if (previous != fold_index) {
          stop("group crosses assessment folds", call. = FALSE)
        }
      }
    }
  }
  if (any(coverage != 1L)) {
    stop("assessment folds are not an exact partition", call. = FALSE)
  }

  fold_imbalance <- max(apply(
    fold_sizes,
    1L,
    function(values) max(values) - min(values)
  ))
  stratum_deviation <- 0
  if (fixture$stratum_count > 0L) {
    for (repeat_index in seq_len(repeats)) {
      for (stratum in seq_len(fixture$stratum_count)) {
        values <- strata_counts[repeat_index, , stratum]
        stratum_deviation <- max(
          stratum_deviation,
          max(values) - min(values)
        )
      }
    }
  }

  quality_primary <- switch(
    case$family,
    stratified = stratum_deviation,
    grouped = fold_imbalance,
    grouped_stratified = {
      totals <- tabulate(
        fixture$strata + 1L,
        nbins = fixture$stratum_count
      )
      objectives <- numeric(repeats)
      for (repeat_index in seq_len(repeats)) {
        objective <- 0
        for (fold_index in seq_len(folds)) {
          for (stratum in seq_len(fixture$stratum_count)) {
            delta <- (
              folds * strata_counts[repeat_index, fold_index, stratum] -
                totals[[stratum]]
            )
            objective <- objective + delta * delta
          }
          size_delta <- folds * fold_sizes[repeat_index, fold_index] - case$n
          objective <- objective + size_delta * size_delta
        }
        objectives[[repeat_index]] <- objective
      }
      max(objectives)
    },
    0
  )
  list(
    quality_primary = quality_primary,
    quality_secondary = fold_imbalance
  )
}

validate_contract <- function(case, fixture, splits) {
  expected_units <- switch(
    case$family,
    kfold = case$folds * case$repeats,
    stratified = case$folds * case$repeats,
    grouped = case$folds * case$repeats,
    grouped_stratified = case$folds * case$repeats,
    monte_carlo = case$repeats,
    bootstrap = case$repeats,
    loo = case$n
  )
  if (length(splits) != expected_units) {
    stop(
      "expected ", expected_units, " units, observed ", length(splits),
      call. = FALSE
    )
  }
  switch(
    case$family,
    bootstrap = validate_bootstrap(case, splits),
    monte_carlo = validate_monte_carlo(case, splits),
    validate_exact(case, fixture, splits)
  )
}

benchmark_case <- function(
    case,
    fixture,
    library,
    version,
    factory,
    warmup,
    measure
) {
  evidence <- validate_contract(case, fixture, factory())
  blackhole <- 0
  if (warmup > 0L) {
    for (iteration in seq_len(warmup)) {
      blackhole <- bitwXor(
        as.integer(blackhole),
        as.integer(consume(factory)$checksum)
      )
    }
  }
  rows <- vector("list", measure)
  for (measurement in seq_len(measure)) {
    start <- bench::hires_time()
    observation <- consume(factory)
    elapsed_ns <- (bench::hires_time() - start) * 1e9
    blackhole <- bitwXor(
      as.integer(blackhole),
      as.integer(observation$checksum)
    )
    rows[[measurement]] <- data.frame(
      protocol = protocol,
      library = library,
      library_version = version,
      runtime = paste(R.version$major, R.version$minor, sep = "."),
      case_id = case$case_id,
      family = case$family,
      contract_id = contract_id(case$family),
      n = case$n,
      folds = case$folds,
      repeats = case$repeats,
      measurement = measurement - 1L,
      elapsed_ns = round(elapsed_ns),
      units = observation$units,
      analysis_ordinals = observation$analysis_ordinals,
      assessment_ordinals = observation$assessment_ordinals,
      fixture_checksum = fixture$checksum,
      semantic_checksum = observation$checksum,
      contract_ok = "true",
      quality_primary = evidence$quality_primary,
      quality_secondary = evidence$quality_secondary,
      stringsAsFactors = FALSE
    )
  }
  if (blackhole == -1L) {
    stop("unreachable anti-dead-code guard", call. = FALSE)
  }
  do.call(rbind, rows)
}

main <- function() {
  if (!requireNamespace("bench", quietly = TRUE) ||
      !requireNamespace("rsample", quietly = TRUE) ||
      !requireNamespace("splitTools", quietly = TRUE)) {
    stop(
      "restore benchmarks/r/renv.lock before running R benchmarks",
      call. = FALSE
    )
  }
  args <- commandArgs(trailingOnly = TRUE)
  manifest <- get_option(args, "--manifest")
  profile <- get_option(args, "--profile")
  warmup <- as.integer(get_option(args, "--warmup"))
  measure <- as.integer(get_option(args, "--measure"))
  output <- get_option(args, "--output")
  cases <- read_manifest(manifest, profile)
  rows <- list()
  row_index <- 1L
  for (case_index in seq_len(nrow(cases))) {
    case <- as.list(cases[case_index, , drop = FALSE])
    fixture <- build_fixture(case)
    factories <- list(
      rsample = rsample_factory(case, fixture),
      splitTools = splittools_factory(case, fixture)
    )
    versions <- c(
      rsample = as.character(utils::packageVersion("rsample")),
      splitTools = as.character(utils::packageVersion("splitTools"))
    )
    for (library in names(factories)) {
      factory <- factories[[library]]
      if (is.null(factory)) {
        next
      }
      rows[[row_index]] <- tryCatch(
        benchmark_case(
          case,
          fixture,
          library,
          versions[[library]],
          factory,
          warmup,
          measure
        ),
        error = function(error) {
          stop(
            case$case_id, " / ", library, ": ",
            conditionMessage(error),
            call. = FALSE
          )
        }
      )
      row_index <- row_index + 1L
    }
  }
  result <- do.call(rbind, rows)
  result <- result[, header, drop = FALSE]
  dir.create(dirname(output), recursive = TRUE, showWarnings = FALSE)
  utils::write.csv(result, output, row.names = FALSE, quote = FALSE)
  message(
    "wrote ", nrow(result), " R measurements for ",
    length(unique(result$case_id)), " cases to ", output
  )
}

if (sys.nframe() == 0L) {
  main()
}
