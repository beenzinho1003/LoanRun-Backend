# Loan rate history — Issue #87 / API #18

`GET /api/loans/{loan_id}/rate-history/` reads the anonymous session owner's V2 rate sources.
It returns HTTP 200 with `LOAN_RATE_HISTORY_SUCCESS`, the specified Korean message and
`data: {loan_id, rates: [{rate_id, loan_id, effective_date, previous_rate, new_rate, source}]}`.
Dates use the stored business date, rates are JSON numbers in annual percent units (6 means 6%),
and source is `MANUAL`. No authentication header or PRO subscription is required.

The complete stored sequence, including the registration rate, is ordered by `effective_date, id`
ascending. Each previous_rate is the preceding row's rate; the first is null because the source
contains no earlier rate. Explicit zero and repeated equal-date/equal-rate rows remain distinct.
The latest loans.annual_rate does not replace or rewrite historical values. An owned visible loan
with no stored rate rows returns an empty array; no rate is fabricated from current metadata.
The shared contract forbids future reservations in rate writes; this read does not create or
validate changes, and API #19 remains pending.

A single owner-scoped LEFT JOIN checks loan visibility and obtains its history. Missing, foreign,
nonpositive and DELETED loan IDs all return 404 LOAN_NOT_FOUND. PAID loans retain visible history.
Malformed or overflowing integer paths use the existing 400 VALIDATION_ERROR envelope. The final
slash is required. Financial data, revision and user timestamps are unchanged by repeated reads;
normal session bootstrap/expiry remains governed by the common anonymous-session implementation.

Only loan-domain code and documentation are changed. There is no migration, new environment variable,
dependency, common port modification or Team 2 code change. LoanQueryPort/PlanQueryPort, rate mutation,
actual budget integration, frontend verification and deployment remain separate work. The tests use
real HTTP/session handling and PostgreSQL; only registration fixture setup uses a test-only budget hook.
