# Report generation admission

`POST /api/reports/` accepts only optional `loan_id` and `force`. It checks current PRO inside the user lock,
captures server-owned input and stores one job for user + scope + financial revision + basis month. Concurrent
equivalent requests reuse that job. PENDING/RUNNING return 202; a current COMPLETED job returns 200 without
another calculation. FAILED requires explicit force to requeue and cannot retry within 60 seconds.

The snapshot includes exact report-scoped historical observations, nullable actual monthly incomes, consent,
plans, performance and captured risk/payment/saving facts. Missing source implementations return 503; household
aggregates are never substituted for a specific loan. No engine, AI or external call runs during capture or while
the user lock is held. Input, attempt and job are committed atomically; a Requested event must be consumed with
AFTER_COMMIT semantics. A rollback must not dispatch work.

`loanrun.reports.max-attempts-per-hour` defaults to 20 per persistent user, shared by report scopes, revisions and
FREE/PRO transitions. Cached responses do not consume another attempt. Infrastructure IP protection and a
bounded worker are separate requirements before live external calls. No live OpenAI call is enabled here.

This change implements admission and persisted requests only. It does not claim a completed calculation or
install placeholder results. Worker execution/calculation/recovery are subsequent changes; do not count API #45
as fully integrated until they are wired and tested. V5/common-contract prerequisites remain under review, so
this prepared fork branch must not become a cumulative central PR.
