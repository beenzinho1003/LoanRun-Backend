# Loan registration — API #13

POST `/api/loans/` resolves the anonymous session before calling the registration service and returns
201 / LOAN_CREATE_SUCCESS with the specified loan view. FREE is supported. The request shares
LoanInputValidator with preview; it cannot select an owner. No login or new financial calculation engine is added.

## Atomic registration

The service resolves optional catalog metadata and computes schedules using the existing engine outside
financial locks. A short write transaction then locks the existing user row, enforces seven ACTIVE loans,
and inserts the loan, registration balance snapshot, initial manual-rate history and first due item.
The due stores the optional bank contractual_amount separately from estimated principal/interest.
It takes precedence for required_amount, including explicit zero; component estimates do not manufacture
confirmed bank breakdowns. Later payment allocation must validate actual components independently.
Zero outstanding principal creates a PAID loan with no future due. Initial BULLET savings are an asset
snapshot, not a repayment_savings transaction or principal reduction. Actual payments remain empty.

The immutable initial MATURITY PlanSnapshot is stored in loans.initial_plan_snapshot with the actual generated
loan ID and computed required BULLET saving. It is the registration aggregate's default plan source for future
V4 versioned-plan work. This change does not create V4, expose plan APIs or implement PlanQueryPort.
The independent annuity/principal anchor is retained separately from the supplied next-payment amount.
Original fee method/rate/exemption are preserved even when the effective simulation fee is exempt.

BudgetRecalculationPort.recalculateFrom runs for the registration month in the same transaction, followed by
one RevisionPort.bump. Any failure rolls back the entire aggregate, due, revision and idempotency response.
No optional no-op provider is installed. Until Team 2 supplies the real budget recalculation implementation,
normal registration returns 503 without saving a loan; success integration tests supply a test-only budget mock.
Even after that provider arrives, its real LoanQueryPort/PlanQueryPort dependencies need integration verification.

## Retry and boundaries

API #13's source request table does not require Idempotency-Key. A supplied key uses the existing common
canonical hash and `(user, POST, /api/loans/, key)` scope. Same key/body returns the exact original 201 response;
a changed body returns 409 IDEMPOTENCY_CONFLICT. Without a key, identical inputs are independent registrations.

A keyed request first probes the common idempotency service in a short transaction. An absent record rolls back
that probe before catalog preparation; a replay returns without catalog/clock revalidation or budget mutation.
The final write transaction checks again after preparation, so simultaneous first attempts still commit once.
An input prepared across KST midnight is rejected rather than saving an outdated snapshot.

Every persisted amount is Long KRW; rate and progress use BigDecimal. Snapshot dates use the injected KST Clock,
and timestamps use Instant. SQL uses bound parameters; financial inputs and exception details are not logged.

## V2 integration

V2 creates loans, balance/rate histories, due_items, payments, payment_allocations and repayment_savings.
Ledger tables establish source constraints and composite owner/loan foreign keys; no payment/saving APIs or
fake actuals are included. Only the first contractual due is initialized. Later source mutations must preserve
past confirmed dues and rebuild applicable future schedules under the same user lock.

The [V2/V3/V5 migration audit](MIGRATION_V2_REVIEW.md) verifies ordered fresh installs and one-off V2
upgrades of earlier V3/V5 test databases, with existing rows/checksums and schema equivalence preserved.
Actual Team 2 DB history is still unknown; use the read-only inspection SQL before operating that DB.
This removes the unverified schema-order gate for central V2 review, not the real budget-port integration gap.

V1 is unchanged. V3/V5 SQL and other owners' migrations are untouched. product_id remains a scalar, matching
Team 2's pending catalog migration; adding catalog/allocation FKs needs a coordinated later migration.
Fresh databases apply V1 then V2. Team 2 databases that already applied V3 or V5 must coordinate the one-time
out-of-order upgrade before adopting V2; production out-of-order is not enabled. Do not modify an applied V2.
Future ledger/plan enhancements require separately reserved migration versions rather than rewriting this baseline.

PostgreSQL tests cover actual registration persistence and JSON, owner FKs, source/default-plan preservation,
concurrent cap enforcement, keyed races, independent unkeyed duplicates, recalculation rollback, retry after failure,
and the missing-provider failure path. Budget arithmetic, actual catalog calls, full query-port integration,
frontend integration and deployment are not established by these tests.
