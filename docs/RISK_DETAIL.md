# Risk detail consumer (API #43)

`GET /api/risks/` is PRO-only. Optional `loan_id` is a positive owned ID; optional `month` is a valid
non-future `YYYY-MM`, defaulting to the server's current Korean month. Exact envelope/code/message and
the existing `RiskDtos.Detail` schema are retained.

Current household scores, checks, thresholds and emergency coverage reuse the risk policies and engine
adapters from API #44. Selection filters events and loan-specific historical changes, while household
indicators/score and shared finance changes remain household-wide. Snapshot reads use repeatable-read;
engine evaluation happens afterward without a financial transaction. No report is generated or revision changed.

Events include rate reset, grace end and BULLET maturity. All known future dates are retained, date/loan/type
order is deterministic, and days 0 through 30 inclusive are imminent. Historical change rows use only
preserved source events in the preceding six completed calendar months. Unknown cash impact stays null;
the consumer does not infer a cash delta from a rate scenario. Counts reflect actual change records.

## Persistence implementation and remaining activation

`RiskDetailHistorySource` has a real, conditionally enabled `RiskHistorySourceAdapter`. Its current
history combines preserved finance changes with owner-scoped Team 1 events (PR #50 remains a proposal).
An observed empty window may have zero net change; an unobserved empty window has null net change.
Past months require exact persisted assessments with owner/selection enforcement. They never invoke current
loan/budget reads or today's engine to invent a historical score. Missing frozen results return 503.

The finance change hooks and confirmed-month assessment writer/storage are implemented separately:
[finance journal](FINANCE_CHANGE_HISTORY.md), [assessment storage](RISK_ASSESSMENT_STORAGE.md) and
[settlement maintenance](RISK_COMPLETED_MONTHS.md). They remain disabled until the unnumbered SQL proposal
is assigned/applied in the agreed migration order and actual Team 1 source ports are supplied. Pending
recalculation hides an older result. No successful empty runtime source is installed.

Verification: `gradlew.bat test --tests com.loanrun.RiskDetailIntegrationTest`. The test runs actual HTTP,
anonymous sessions, PostgreSQL revision storage and risk arithmetic. Ledger/risk input/history providers
are test-only mocks; it verifies source composition and frozen-history isolation, not live source completeness.
