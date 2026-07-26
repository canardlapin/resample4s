source("benchmark.R")

cases <- read_manifest("../cases.csv", "smoke")
if (nrow(cases) != 7L) {
  stop("expected seven smoke cases", call. = FALSE)
}

grouped_case <- as.list(
  cases[cases$case_id == "grouped-stratified-smoke", , drop = FALSE]
)
grouped_fixture <- build_fixture(grouped_case)
if (!identical(grouped_fixture$checksum, 128298438)) {
  stop(
    "R fixture checksum does not match Scala/Python: ",
    grouped_fixture$checksum,
    call. = FALSE
  )
}

validated <- 0L
for (case_index in seq_len(nrow(cases))) {
  case <- as.list(cases[case_index, , drop = FALSE])
  fixture <- build_fixture(case)
  factories <- list(
    rsample = rsample_factory(case, fixture),
    splitTools = splittools_factory(case, fixture)
  )
  for (factory in factories) {
    if (is.null(factory)) {
      next
    }
    evidence <- validate_contract(case, fixture, factory())
    if (evidence$quality_primary < 0 ||
        evidence$quality_secondary < 0) {
      stop("quality evidence must be non-negative", call. = FALSE)
    }
    validated <- validated + 1L
  }
}
if (validated != 11L) {
  stop("expected eleven supported R smoke cells, observed ", validated)
}

message("R benchmark protocol tests passed (", validated, " cells)")
