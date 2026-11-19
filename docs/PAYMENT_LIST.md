# Payment list — Issue #91 / API #24

`GET /api/payments/` returns HTTP 200 PAYMENT_LIST_SUCCESS with the specified Korean message.
Only the anonymous session owner's actual payment rows are eligible; there is no client user selector
or PRO requirement. The response has count, page, page_size, next_page, previous_page, results and totals.
Each results item reuses the API #28 detail shape and conversion, including original fee/savings-use
amounts, KST created_at, snapshot-date editability, representative due and every persisted allocation.

Optional filters: loan_id (positive bigint), from/to (strict YYYY-MM-DD, inclusive paid_at boundaries),
and type (REGULAR/EXTRA). Either date may be omitted. Filters combine with AND and use the user's
recorded payment date, not created_at or due_date. No records are synthesized from schedules or plans.
PAID/DELETED loans retain their actuals. An owned loan with no matching payments returns an empty list;
a foreign/missing loan filter returns 404 LOAN_NOT_FOUND without revealing whether it exists.

Pagination is one-based: page defaults to 1 and page_size to 20, accepted sizes 1–100. Both use positive
32-bit integers; offset calculation uses long to avoid integer overflow. Records are ordered by paid_at
then payment ID, both descending, preserving distinct equal-date/equal-amount payments. This deterministic
order is an implementation choice where the detailed API specifies no alternative sort order.
An out-of-range page returns 200 with empty results and the actual count/totals. next_page is null when
there is no subsequent page; previous_page is page-1 when page>1, otherwise null. An unfiltered empty
query has count=0, zero query totals and an empty results array. These query sums do not assert a confirmed
zero-performance observation month for financial projections.

Totals cover the entire filtered payment set, independently of the page: amount, principal_amount,
interest_amount, extra_amount, fee_amount and savings_used_amount. Amount includes all four stored
payment components; savings use is reported separately and is not subtracted from recorded amount.
PostgreSQL numeric SUM is converted exactly to long; an unrepresentable aggregate returns 503 rather
than a wrapped, truncated or partial financial amount.

The repository executes owner validation (if filtering a loan), count/totals and paged detail retrieval
inside a single read-only REPEATABLE_READ transaction. Aggregates use payments without allocation joins.
A CTE pages payments before joining their allocations, so multi-due payments neither inflate sums/counts
nor consume multiple page slots. The selected page's allocations are retrieved together, without a
per-payment detail-query loop. Missing allocations retain the API #28 null/empty-array behavior.
As with other read repositories, an existing caller transaction owns its isolation guarantees.

Invalid dates/ranges return 400 INVALID_DATE_RANGE. Invalid numbers, enum values and pagination return
the common 400 VALIDATION_ERROR envelope. The final slash remains required. The read changes no financial
source, revision, idempotency record or user timestamp. Shared session bootstrap/access behavior is unchanged.
No DB migration, dependency, environment variable, common interface or Team 2 code is changed.

Real PostgreSQL/HTTP tests cover the exact empty response, stable duplicate ordering, full-filter totals
across pages, multi-due payments at a page boundary, list/detail equality, combined/inclusive/one-sided
filters, owner isolation, PAID/DELETED history, snapshot editability, input errors, default/max sizes,
large offsets, long aggregates/overflow and read-only behavior. Existing detail tests also pass.
Payment create/update/delete, schedule/monthly summaries, LoanQueryPort/PlanQueryPort and real budget
integration remain separate features; their absence does not block merging this complete read API.
