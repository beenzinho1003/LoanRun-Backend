# Stored due states — Issue #95

StoredDueRepository reads actual V2 due rows and their allocation component sums with explicit user/loan
joins. StoredDueQueryService evaluates those facts on one captured KST date per call. Immutable source
and projection records keep original estimated components, contractual_amount (including null vs 0),
required_amount, confirmation, terms revision, creation time and current stored loan status separate
from allocated principal/interest/fee and derived state. Each allocation is counted once per due even
when one payment spans multiple dues or several payments fulfill one due.

The pure DueStateCalculator applies this order:

1. required amount fully met (including explicit zero required amount): PAID.
2. Stored actual allocation rows but still outstanding: PARTIAL, even after the due date or confirmation.
3. No allocations and due date today/future: UPCOMING (the due day is inclusive).
4. Past, unallocated and explicitly confirmed: UNPAID.
5. Past, unallocated and unconfirmed: UNRECORDED.

Actual allocation includes regular principal + interest + fee; it excludes payment.extra_amount and
savings-use reporting. Using savings changes the cash funding source, not required due fulfillment.
The raw allocated amount remains unchanged. paidRequiredAmount caps the fulfilled portion at required;
outstandingAmount is required minus that capped amount. Over-allocation is preserved as a source fact,
not turned into an extra repayment or silently removed. A saved zero-valued allocation row is still a
record, so an outstanding obligation cannot be treated as unrecorded for confirmation eligibility.
EXTRA without an allocation has no effect on a regular due. An invalid saved EXTRA allocation fails
with SERVICE_UNAVAILABLE rather than being counted as regular fulfillment. Aggregate overflow also
fails instead of wrapping/truncating amounts. No ledger repair is attempted by a read.

find(userId,dueId) owner-scopes the source and returns DUE_ITEM_NOT_FOUND for foreign/missing IDs.
range(userId,DateRange,optionalLoanId) filters inclusive scheduled due dates and orders by due date/ID.
An owned empty range returns an empty immutable list. A foreign/missing loan filter returns LOAN_NOT_FOUND.
Internal history reads retain PAID/DELETED loan sources with their current stored status; these facts
are not assertions about a past loan/deletion/confirmation state. Public mutating confirmation retains
its existing DELETED exclusion and ACTIVE requirement.

The read-only REPEATABLE_READ boundary covers source retrieval and state evaluation. Inside an existing
transaction the caller owns its isolation/locks; annotations do not upgrade the transaction. #31 first
holds the common user lock and due row lock, then uses this same state evaluation for eligibility and
retains its guarded update. Partial/full states take precedence over an older confirmation flag.

No flag, payment, savings, contract component, balance, revision or user timestamp is written by these
queries. They read stored dues only: no guessed monthly/overdue rows, no bank values replaced by estimates,
no inferred observed month, and no claim of complete future contractual coverage. Empty stored ranges
must not be used as a zero scheduledCash assertion. No LoanQueryPort provider is installed by this feature;
terms/balance replay, schedule coverage and observation/monthly summaries remain separate integration work.

#31 also corrects unchanged confirmation handling: a new key with an already stored value returns success
without recalculation/revision or another flag write, with report_stale=false for no additional invalidation.
It still stores the replay response under the shared key scope. Actual changes retain mandatory budget
recalculation and report invalidation. The same original key always replays its original response.
The report-read API remains authoritative about an existing report's current freshness.

Tests exercise the five states, due-day boundary, partial/full precedence, explicit contractual zero,
fee-only fulfillment, raw/capped amounts, multi-payment/multi-due sums, source ownership/history, EXTRA
separation, overflow, immutable/read-only behavior and no-change confirmation without a budget provider.
No new HTTP path, migration, common contract, dependency, environment or Team 2 change is included.
