# Stored strategy queries

API #50 reads a completed strategy snapshot; it never generates a report or invokes the engine/AI. Without
report_id it selects the newest household COMPLETED report by report ID, excluding individual reports and
newer unfinished jobs. Explicit IDs are owner-scoped. PRO is required. Stale revision/month and unfinished
reports return an empty candidate list and the specified STALE/REPORT_NOT_READY reason.

The existing StrategyQueryPort is implemented for Team 1 preview/apply. It checks owner, current PRO,
revision, basis month, completed status, stored strategy version/code and candidate feasibility before returning
the persisted allocations and engine result. Rejected snapshots produce STRATEGY_STALE; another owner
sees REPORT_NOT_FOUND. Callers retain their existing user mutation lock/version checks when applying.

No schema or shared interface changes. V5 and UserAccessPort remain prerequisites; this prepared fork
branch is not submitted as a cumulative central PR. Candidate calculation and the full report adapter remain
separate unfinished work. Integration test snapshots are fixtures, never production-generated financial data.
