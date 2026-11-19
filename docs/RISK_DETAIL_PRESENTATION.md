# Risk event/history presentation

`RiskDetailPresentation` maps preserved inputs to API #43's existing event/change DTOs without database
access, loan calculations or inferred historical cash effects. It adds no HTTP endpoint or runtime source.

- Known future rate reset, grace end and BULLET maturity dates are retained and sorted by date/loan/type.
  Today and day 30 are imminent; day 31 remains visible without the imminent flag.
- The change window is the six completed calendar months before the requested basis month. The selected
  loan filters loan-specific rows; shared household finance changes remain included.
- Signed recorded cash deltas are summed with exact arithmetic. Any unknown included cash delta makes
  the net unknown. Rate changes with unknown cash impact still retain their record and count.
- An unobserved empty window has null net change. An observed empty window has zero recorded net change.

The accompanying internal `RiskDetailHistorySource` describes immutable source observations and exact
frozen past assessments. Its implementation must enforce owner/scope and preserve source history. No
default provider or successful empty-data replacement is registered. Source persistence and the consumer
endpoint remain separate integration work; this helper does not establish API/runtime completion.

Verification: `gradlew.bat test --tests com.loanrun.RiskDetailPresentationTest` (four pure boundary tests).
