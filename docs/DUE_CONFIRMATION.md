# Due confirmation — Issue #93 / API #31

`PATCH /api/payments/schedule/{due_item_id}/confirmation/` accepts exactly one JSON boolean field,
`unpaid_confirmed`, and requires Idempotency-Key (1–128 nonblank characters). It uses the existing
anonymous session and Origin checks; neither authentication tokens nor PRO are required.

Only an owner's past, unallocated regular due with a positive required_amount and an ACTIVE loan is
mutable. Due date must be strictly before today in the injected Asia/Seoul Clock; today's due remains
UPCOMING. A zero contractual amount is already satisfied, even when estimated components are positive.
Existing allocations (partial/paid, including fee-only or zero-valued allocation rows) block confirmation;
this operation never rewrites their actuals or interprets an extra repayment as regular fulfillment.
Missing/foreign/deleted scheduled resources return 404 DUE_ITEM_NOT_FOUND. Paid-loan or allocated/zero
obligations return 409 DUE_CONFIRMATION_CONFLICT; today/future unrecorded dues return 400
DUE_CONFIRMATION_VALIDATION_ERROR. These checks precede mutation/dependency invocation.

true changes the persisted confirmed_unpaid flag and returns UNPAID; false returns UNRECORDED.
Both use HTTP 200 DUE_CONFIRMATION_SUCCESS and the specified message. The response contains due_item_id,
loan_id, due_date, required_amount, paid_required_amount=0, outstanding_amount=required_amount,
unpaid_confirmed, status, display_status and report_stale (true for a flag change, false for no change).
Zero paid amount follows the absence
of persisted due allocations; no payment, bank transfer, interest charge, principal or savings reduction
is manufactured. Due financial components/terms_revision/creation timestamp remain intact.

The transaction follows the common mutation contract: user lock/idempotency lookup → owner-scoped due
row lock → eligibility recheck → guarded flag update → required BudgetRecalculationPort from the due
month → one revision bump → durable response. The update also checks the prior flag/date/amount, ACTIVE
loan and absence of allocations. The new revision makes previously generated reports stale; this API
does not generate a report. Real partial/full payment operations remain separate features.

The shared key scope is user + PATCH + exact resource path + key. An identical key/body returns the
original response before current state/provider checks and performs no new recalculation/revision.
Different body with the same key returns the specified 409 conflict envelope. A different due/user has
an independent key scope. An eligible new-key request with the same stored value succeeds and stores
its replay response, but performs no flag write, recalculation or revision bump. report_stale=false
means this request did not newly invalidate reports; it does not certify that an existing report is
fresh. Existing stale state is retained and the report-read revision check remains authoritative.
Concurrent identical requests commit one response through the existing user lock; only an actual
flag change advances revision once. This follows FINAL_DECISIONS: revision changes only with data.

Required recalculation for a changed flag is not skipped. Without a real budget provider such a request
returns 503 and saves no flag, revision or replay response. A valid unchanged request needs no budget provider. Recalculation/revision/storage failures roll back all transaction
writes; the same key can then be retried. Controlled success tests use a test-only budget mock with real
HTTP, sessions, user locking, idempotency and PostgreSQL. A separate normal context verifies missing-provider
503 without placeholders. This distinction is implementation/test verification, not actual budget integration
or deployment. No common interface, migration, environment variable, dependency or Team 2 code is changed.

Tests cover exact confirm/cancel responses, source/asset invariance, KST overdue boundaries, partial/paid/
zero obligations, ownership/deletion/paid loans, strict booleans/keys/routes, durable replay/conflict,
key/user/resource scope, reconfirmation, extra-versus-regular separation, dependency rollback/retry and
concurrent duplicates. Actual budget/query-port integration remains required before live flag-changing success.

Issue #95 routes eligibility through StoredDueQueryService and the same five-state calculator used by
the internal stored-due projections. Row/user locks, guarded updates and durable replay behavior are
unchanged. Reconfirmation no longer advances revision solely because it used a new request key.
