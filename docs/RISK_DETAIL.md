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

## Remaining persistence integration

`RiskDetailHistorySource` is an internal Team 2 source boundary, with no default provider. Its current
history must combine preserved finance changes with owner-scoped Team 1 events (PR #50 remains a proposal).
An observed empty window may have zero net change; an unobserved empty window has null net change.
Past months require exact persisted assessments with owner/selection enforcement. They never invoke current
loan/budget reads or today's engine to invent a historical score. Missing frozen results return 503.

The immutable finance-change history and historical assessment writer/storage are still required. This
consumer does not implement them, reserve a new SQL version, or silently install empty successful data.
Do not count this as complete runtime API integration until those sources are implemented and verified.

Verification: `gradlew.bat test --tests com.loanrun.RiskDetailIntegrationTest`. The test runs actual HTTP,
anonymous sessions, PostgreSQL revision storage and risk arithmetic. Ledger/risk input/history providers
are test-only mocks; it verifies source composition and frozen-history isolation, not live source completeness.
