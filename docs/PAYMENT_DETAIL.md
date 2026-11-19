# Payment detail — Issue #89 / API #28

`GET /api/payments/{payment_id}/` reads the anonymous session owner's actual V2 payment.
HTTP 200 uses `PAYMENT_DETAIL_SUCCESS` and the specified Korean message. The response includes
payment_id, loan_id, paid_at, amount, payment_type, due_item_id, principal_amount, interest_amount,
extra_amount, created_at, fee_amount, savings_used_amount, editable, delay_days and due_allocations.
The memo remains an internal source field; it is not an additional API field.

Amounts remain the stored actual KRW values. amount includes regular principal, extra principal,
interest and fee; using savings does not reduce this recorded amount. created_at is converted from
the original Instant to Asia/Seoul (+09:00), while paid_at remains the user's actual business date.
The read does not simulate repayments or change balances, dues, savings, plans or revision.

Persisted allocations are ordered by due_date then due-item ID. The representative due_item_id
and top-level delay_days refer to the oldest allocated due, consistent with API #25's first-target
rule. Every allocation is returned with required_paid_amount = allocated principal + interest
(the API definition excludes fee) and delay_days = max(0, paid_at - due_date). Actual payment fees
remain in fee_amount; they are not principal or an estimated late-interest charge.
An EXTRA without allocations has null due_item_id/delay_days and an empty due_allocations array.
A REGULAR source with no saved allocations also retains those unknowns; matching a nearby scheduled
date would fabricate a relationship and is deliberately avoided.

Missing, foreign and nonpositive payment IDs all return 404 PAYMENT_NOT_FOUND. Loan status does not
hide preserved actuals: PAID and DELETED records remain readable by their owner. editable is false
for paid_at before the loan's latest snapshot_date and true on/after it, following the public
contract's date boundary. This field reports that boundary; mutation endpoints and transactional
replay/concurrency validation are separate work and are not enabled by this read.
Malformed/overflowing integer paths use the existing 400 VALIDATION_ERROR envelope. URLs require
the final slash. No user ID, authentication header or PRO subscription is accepted or required.

A single SQL statement scopes the payment, loan, allocations and due joins by owner/loan keys.
It reads the payment, snapshot date and allocation sequence together, including soft-deleted loans.
It does not parse unrelated initial-plan snapshots or depend on incomplete budget/query providers.
There is no migration, dependency, environment variable or shared interface change.

The PostgreSQL/real HTTP tests use synthetic V2 fixture rows only; no runtime placeholder or mock
budget/query provider is installed. They cover exact JSON, stored component amounts/savings use,
UTC-to-KST dates, multiple/early/missing allocations, EXTRA, ownership, missing IDs, PAID/DELETED,
snapshot-date boundaries, malformed routes, immutable sources and repeated-read financial invariance.
Payment list/create/update/delete, monthly schedules/summaries, LoanQueryPort/PlanQueryPort, actual
budget integration, frontend verification and deployment remain pending separately.
