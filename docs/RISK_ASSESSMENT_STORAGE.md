# Confirmed risk assessment storage and history source

When history is enabled after schema coordination, `RiskHistorySourceAdapter` supplies API #43 with actual
owner-scoped finance changes and Team 1 rate events. It keeps unknown rate cash impacts null, never assigns
today's score to an old change, and distinguishes confirmed empty windows from unobserved windows using
stored budget settlement state. The observation marker does not depend on creating a risk result.

`RiskAssessmentStore` accepts only confirmed past month-end sources: settled budget, confirmed expenses,
known actual income and observed payments/savings for that exact month. It saves household and owned
loan-detail projections under one version. Selected details must retain household score/indicators. Evaluation
runs before the short write phase; the write locks the owner and rejects a changed captured revision.

Corrections append immutable versions. Same-input retries retain the existing version. A mutable owner/month
request marker separately tracks invalidation: a stale or failed evaluation leaves the marker pending, so an
old immutable result cannot be served as the corrected result. Clearing the marker and saving a successful
assessment commit together; older completions cannot clear a newer request.

Frozen reads return stored values with current server PRO checks, not current financial calculations. Owned
stored selector keys remain usable for historical paid loans; other selections are 404. Missing assessments
or pending recalculation are 503. No household totals are fabricated for an individual loan.

Six PG/HTTP storage/source tests verify exact frozen reads, FREE/foreign isolation, revision conflicts, retained
versions, confirmed-month checks and actual-event/null semantics. Seven repository tests also cover the
transactional invalidation marker. Source providers remain test-only where Team 1 integration is pending.

The request table is a follow-up to the initial two-table SQL review. All SQL remains unnumbered under
`docs/sql/` and is applied only in isolated test databases. Do not enable production history until migration
version/order and actual owner-scoped source providers are agreed and integrated.
