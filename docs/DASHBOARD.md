# Dashboard consumer (API #42)

`GET /api/dashboard/` accepts an optional positive `loan_id`. The server session selects the owner and
server state selects FREE/PRO. DTOs are separate from the proposed source interface in PR #84.

The source supplies the complete owned loan selector, selected-scope totals/forecast/dues/monthly summary
and checklist. The consumer copies those values without implementing loan calculations, preserves null
forecasts, separates savings from actual repayment, and sorts dues by date/ID. Foreign/missing selection is
404. Source unavailability is 503; a successful empty placeholder is never installed.

PRO risk uses all active household loans and the same risk arithmetic as API #44, regardless of the loan
selector. FREE returns locked risk with null monetary/score values and hides report identifiers/dates/N.
Report status is owner/scope-specific, never starts a job, retains the last successful date while a later
job is pending/failed, and uses the existing revision/month/24-hour badge policy.

Inputs are captured in a short repeatable-read transaction. Risk engine probes run afterward. HTTP reads
do not bump revision, write financial records, or generate reports.

Verification: `gradlew.bat test --tests com.loanrun.DashboardIntegrationTest --tests com.loanrun.DashboardReportStatusPolicyTest`.
The HTTP test uses PostgreSQL, real sessions/revisions/report storage and test-only source/risk-input mocks.
It does not establish that Team 1's source provider, frontend integration or deployment is complete.

Integration dependencies remain PR #84 agreement/provider and the report/finance migration order. Keep
the consumer commit isolated on the Fork until its dependencies can be integrated as small PRs.
