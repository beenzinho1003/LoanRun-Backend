# Risk history storage proposal

The unnumbered [SQL proposal](sql/risk_history_proposal.sql) adds two Team 2 tables. It is outside Flyway
discovery: agree on the next version and V3/V4/V5 order before moving it to production migrations. Existing
DBs and migration checksums are not changed. Repository construction does not access the proposed tables.

`finance_change_events` preserves owner/revision/origin, effective month/event date, income/expense kind,
nullable before/after amounts and whether a cash effect is known. Source amounts are not historical risk
scores. `risk_assessment_versions` stores exact completed-month household and per-loan presentations,
source revision/hash and month-end basis. Corrections append another version and retain previous versions.

Database triggers prohibit updates/deletes of both journals. Identical appends reuse the original version
without altering its timestamp; conflicting payloads return `409 RISK_HISTORY_CONFLICT`. Snapshot JSON
key order is ignored for replay equality. All reads filter the server owner; null and confirmed zero differ.
Writers require an existing transaction. Financial mutation hooks must hold the user lock, append within the
same transaction and use its revision. Assessment orchestration must capture confirmed historical sources,
evaluate outside the financial transaction, then lock/recheck revision before appending.

`RiskHistoryRepositoryTest` applies only this proposal to an isolated PostgreSQL Testcontainer and covers
rollback, replay/conflict, owner filtering, preserved versions, concurrent duplicate writes and DB constraints.
It does not activate runtime history, connect mutation hooks, or confirm actual application migration order.
