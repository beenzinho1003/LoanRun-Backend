# Loan source queries — Issue #82

LoanSourceRepository reads the actual V2 sources into immutable LoanSources records. This is the source
retrieval layer for subsequent LoanQueryPort projections. It does not implement that port, expose HTTP
endpoints, calculate balances/interest/statuses, call budget services, or install runtime empty-data providers.

## Owner and date boundaries

- loan(userId,loanId) filters both identifiers in SQL. Missing/foreign IDs return the same 404 LOAN_NOT_FOUND.
  This internal source method includes PAID and DELETED; public view adapters must apply their visibility rules.
- snapshot(userId,actualsThrough) includes loans registered by the supplied KST business date, then owner-scopes
  every child collection to those loan IDs. Registration timestamps are converted with Asia/Seoul in PostgreSQL;
  a UTC previous-day timestamp cannot move a KST registration into the wrong business day.
- Balance/rate sources use effective_date <= cutoff. Actual payments and saving additions use paid_at/saved_at
  <= cutoff. Stable ordering is original date then ID; equal-date/equal-amount records remain separate.
- Contractual due rows remain available, including future dates, so an early actual payment can still reference
  its future due. Allocation inclusion follows the actual payment date, never the due month. Future actual
  payments/allocations are excluded by the business cutoff, while current persisted contractual sources remain.
- payments(userId,range) and savings(userId,range) use inclusive actual date ranges and retain PAID/DELETED loan
  records. No list status filter silently removes historical actuals.

The snapshot is a consistent read of currently stored source rows with business-date filters. It is not a
historical materialized financial state. Loan.storedRemainingPrincipal, storedStatus, storedNextDueDate,
latestSnapshotDate and the current annual rate remain stored latest metadata. Due.confirmedUnpaid is also
its stored flag, not a reconstructed past confirmation. Backdated effective histories and corrected actuals
remain original facts; the repository does not infer historical deletion/confirmation time or resolve same-day
correction/replay ordering. Those rules require projection/ledger work and adequate source evidence.
Standalone calls use read-only REPEATABLE_READ. When invoked within an existing transaction, the caller owns
its isolation/lock guarantees; the annotation does not silently upgrade an already-open transaction.

## Preserved values

Nullable product/contractual/fee/reset fields remain null. Explicit zero and false remain present. Original
fee method/rate/exemption are separate facts; this layer does not replace an exempt original contract with
another fee method. Initial BULLET savings remain an asset field and do not become a saving transaction.
Actual principal, extra principal, interest, fee, savings-used and memo values are preserved independently.
No cash-outflow total, paid status or monthly observed flag is manufactured from these records.

The registered MATURITY PlanSnapshot is parsed into the existing immutable contract record. An invalid,
missing, wrong-loan or negative-goal snapshot fails with SERVICE_UNAVAILABLE rather than inventing defaults.
Empty child collections mean no matching persisted rows, not confirmed zero actual performance.
Lists are defensively copied and source reads do not write dues, ledger entries, revision or user timestamps.

## Validation and integration status

Real PostgreSQL tests cover owner filtering/404, UTC–KST creation boundaries, inclusive ranges, original
same-day order, explicit zero/null/false, fee and savings-use preservation, future due/actual separation,
PAID/DELETED history, invalid plan source handling, immutable results and read-only behavior. Financial
source fixtures are synthetic; budget mocks exist only in registration test setup.

This feature uses the V2 baseline merged through #80 at c275347. Central develop has been merged into this
feature branch for retesting and a separate develop PR. No registration code is duplicated in its diff.
No migration, shared contract, public API or Team 2 code changes are included.
LoanQueryPort financial projection/aggregation, default/versioned PlanQueryPort, real budget recalculation,
public loan reads, frontend integration and deployment remain separate work. This layer alone does not remove
registration's missing-provider503 or activate CSV/risk domain integration.
