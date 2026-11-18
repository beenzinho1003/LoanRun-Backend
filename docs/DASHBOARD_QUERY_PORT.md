# Dashboard source projection proposal — Team 1 agreement required

This read-only interface supplies the Team 1 ledger/plan portion of API #42. It adds no implementation,
runtime empty-data fixture, HTTP endpoint, schema or new loan calculation. Keep it Draft until both owners
agree on fields/units, source completeness and implementation order. Existing port signatures stay unchanged.

snapshot(userId, selectedLoanId, asOf) must be owner-scoped; foreign/missing or excluded deleted selections
produce LOAN_NOT_FOUND. The owned selector list remains complete (including supported PAID display),
while totals, progress, forecast date, nextPayment, monthlySummary and checklist honor the selected scope.
The provider decides the source mapping; it must not infer individual totals from household aggregates.

Use the same contractual dues, payments, savings, frozen plan versions and current monthly overrides as
the source APIs. Preserve UPCOMING/PAID/PARTIAL/UNRECORDED/UNPAID exactly. Extra/saving goal shortfalls
are not missed required payments. Sort the checklist by due date with deterministic ID ties. An unknown
forecast/progress remains null; no rows and confirmed zero are distinct from an unavailable source. If the
specified non-null source fields cannot be supplied, fail explicitly instead of installing a successful stub.

Savings remain distinct from principal repayment; do not count reused saving as fresh cash. Progress is based
on the specified original principal, not an invented cumulative-payment estimate. Team 2 separately composes
server plan gates, household risk and owner-scoped report status/is_new. No source query generates reports.
