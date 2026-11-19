# Implementation audit — 2026-11-18 KST

Audited central develop at `579141fc600e3594ad3c6592b60dd2d3b89f7652`, its Git history, existing tests, and
the currently visible Team 2 Fork branches/issues/Draft PRs. A feature branch or issue describing work does
not establish that its implementation is available in central develop. The private handoff package remains local.

## Central implementation before this change

| Area | Verified repository evidence | Remaining integration |
|---|---|---|
| Common project | Bootstrap PR #1; Java 21, Boot/Gradle, V1, anonymous sessions, envelope, Origin checks, locks, revisions, idempotency | Reuse existing application and contracts |
| User/settings | GET/PATCH profile, subscription, notifications: six mapped API operations | Existing local integration tests passed |
| CSV export | PR #14, API #54 | Real LoanQueryPort absent; fail with 503 rather than fixtures |
| Risk scenario | PR #43 and subsequent refactors, API #44 | Real loan/plan/budget providers and engine absent at baseline; adapter tests used controlled inputs |
| Pure policies | Budget cash, risk math, report statistics, recommendation/input policies, strategy scope/input builders | These helpers are not completed HTTP endpoints |
| External adapters | FSS normalizer and explanation client/config | No actual external call or complete report worker demonstrated in central develop |
| Runtime packaging | Container/HTTPS Compose and CI configuration | Configuration is not production deployment |

Eight product API method/path operations are mapped in central develop. Six operate on the common user/settings
state; CSV and risk retain missing-domain dependency checks. This is not an end-to-end claim for all eight.
The original implementation target remains 50 operations; authentication/account deletion are excluded.

## Existing Team 2 work outside central develop

The Fork has separate feature branches for finance profile, onboarding, catalog, monthly budgets, income entries,
allocation, comparison settings, report storage/read/generation/worker/recovery and strategy read/calculation.
The report preparation tree at `da7dd5ad33a939fa6c0db674c9250da515d813cf` contains report controllers and V5,
which are absent from central develop. Their previously reported tests have not been rerun in this checkout.
Do not reimplement these Team 2 areas or merge their cumulative preparation branches as part of Team 1 work.

Relevant open coordination PRs at audit time:

- [#15: V3 finance/budget/catalog schema](https://github.com/LoanRun-Team/LoanRun-Backend/pull/15)
- [#29: access and monthly-plan ports](https://github.com/LoanRun-Team/LoanRun-Backend/pull/29)
- [#46: V5 report schema](https://github.com/LoanRun-Team/LoanRun-Backend/pull/46)
- [#50: history query ports](https://github.com/LoanRun-Team/LoanRun-Backend/pull/50)

These are coordination dependencies, not authorization to change shared contracts. Only V1 is in the audited
central migration directory. Keep the V2/V3/V4/V5 reservations and coordinate ordering before integrating schemas.

## Team 1 work

| Feature | Baseline | This change / next dependency |
|---|---|---|
| Pure RepaymentEngine | Interface only; no implementation | Issue #74 adds schedules, fees, savings, allocation and inverse targets through existing signatures |
| Loans / rates, API #12–19 | No controllers, services, repositories or V2 | Next: loan preview and owner-scoped persistence/query projections |
| Payments / due ledger, API #24–31 | Not implemented | Needs loans, source snapshots, frozen dues and transactional replay |
| Repayment savings, API #32–35 | Not implemented | Needs shared loan/payment savings ledger and budget recalculation |
| Plans / overall, API #20–23 | Not implemented | Needs source queries, budget capacity/overrides, strategy validation and V4 |

All 24 Team 1 HTTP operations remain pending after this engine-only change. No bootstrap, shared interface,
migration, existing endpoint, dependency or environment variable is changed. Each subsequent feature should
have its own central Issue, personal Fork branch `<type>/<issue>-<english-description>` and develop PR.

Baseline validation in this checkout: Java 21 `./gradlew clean build`, 100 tests, failures/errors/skips 0,
including PostgreSQL Testcontainers. Engine validation and exact final counts are recorded in its PR.
No frontend, Notion, Figma, production database or deployment was modified.

## Follow-up: loan preview (Issue #76)

After engine PR #75 merged at f9dcb78, API #14 POST /api/loans/preview/ and reusable LoanInputValidator were added. No V2 or actual registration API is included. The 24-operation baseline above remains historical; 23 Team 1 HTTP operations remain pending after this follow-up. Product ID lookup requires the real catalog port; manual input needs no external service. Preview leaves financial revision and idempotency records unchanged, verified with PostgreSQL and real HTTP requests.

## Follow-up: registration (Issue #79)

API #13 and V2 source storage are implemented with user locking, the active-loan cap, initial MATURITY snapshot, first due and atomic recalculation/revision. Success tests exercise the actual PostgreSQL repository and HTTP endpoint with a test-only budget provider; the normal runtime remains blocked with503 until Team2 supplies the real recalculation/query chain. No runtime placeholder provider, LoanQueryPort or PlanQueryPort is introduced. V3/V4/V5 remain unchanged; existing V3/V5 environments require coordinated migration ordering. Other Team1 HTTP operations (#12, #15–35) remain pending.

## 2026-11-19 migration review for #80

The pinned V1/V2/V3/V5 SQL combination passed five isolated PostgreSQL/Flyway11.7.2 audit cases: fresh order, V3/V5-first V2 introduction, existing-row/history preservation, schema equivalence, restart with default ordering and invalid-file validation. See MIGRATION_V2_REVIEW.md and the read-only psql script. Actual Team2 DB state has not been supplied. Central V2 review can proceed separately from that DB migration and real budget/query-port integration. No actual development/production DB, shared SQL or application setting was changed.

## Follow-up: source reads (Issue #82)

LoanSourceRepository/LoanSources add real owner/date-scoped V2 reads and immutable typed facts. PostgreSQL tests cover ownership, dates, original values and no financial writes. After #80 merged at c275347, the source-read feature was integrated with central develop and retested for a separate PR. It is not a completed LoanQueryPort; financial projection/aggregation and public loan query APIs remain pending.

## Follow-up: rate history (Issue #87)

After source-read PR #86 merged at 825c582, API #18 GET /api/loans/{loan_id}/rate-history/ adds a public,
owner-scoped read of stored manual rate history. The registration rate is included; previous_rate is null
for the first row and otherwise derived by effective_date/id order, preserving zero and duplicate values.
DELETED/foreign/missing loans return 404; PAID history remains visible. No runtime port, schema, mutation
or Team 2 code is changed. Team 1 has three HTTP operations implemented (#13, #14, #18), with #13 still
requiring the real budget recalculation provider. The other 21 Team 1 operations, LoanQueryPort financial
projections, PlanQueryPort, frontend integration and deployment remain pending. Validation counts are
recorded in the feature PR.

## Follow-up: payment detail (Issue #89)

After rate-history PR #88 merged at c179067, API #28 GET /api/payments/{payment_id}/ reads the session
owner's stored payment and all due allocations through a single owner-scoped SQL statement. Original
amounts/fees/savings use, KST timestamps, per-due delay and snapshot-date editability are preserved.
PAID/DELETED loan history remains visible; foreign/missing records return 404. This read requires no
budget/query provider and makes no financial writes. No schema/shared contract or Team 2 code changes.
Team 1 now has four HTTP operations implemented (#13, #14, #18, #28); #13 still requires the real
budget provider. The remaining 20 Team 1 operations and financial query-port/budget integration are
pending. This does not implement payment writes, frontend integration or deployment. Test results are
recorded in the feature PR.

## Follow-up: payment list (Issue #91)

Payment-detail PR #90 was verified independently complete with CI66 success and merged at 462efcc.
API #24 GET /api/payments/ reuses its item conversion and adds owner-scoped actual-date/type/loan
filters, stable paid_at/id descending pagination and full-filter count/component totals. Paginating
payment rows before allocation joins preserves all allocations without duplicated totals or shortened
pages. PAID/DELETED history is retained; foreign/missing loan filters return 404. Both reads require
no budget provider and introduce no runtime placeholder, migration, shared contract or Team 2 change.
Team 1 now has five HTTP operations implemented (#13, #14, #18, #24, #28); #13 still awaits real budget
recalculation. The other 19 operations, financial query projections and budget integration are pending
as separate features, not blockers to merging these completed read APIs. Frontend/deployment remain
unverified. Local and CI results are recorded in the feature PR.

## Follow-up: due confirmation (Issue #93)

After payment-list PR #92 merged at d1771d4, API #31 adds mandatory-key owner-scoped overdue due
confirmation/cancellation using the shared user lock and durable idempotency service. Partial/paid/zero
obligations are rejected, existing ledger/assets remain intact, and the flag/recalculation/revision/response
are atomic. PostgreSQL/HTTP success tests use only a test budget hook; normal runtime has no replacement
provider and returns 503 without persisting until real recalculation/query integration is available.
No schema, common interface, environment/dependency or Team 2 code changes. Team 1 has six operations
implemented (#13, #14, #18, #24, #28, #31), with #13/#31 integration-blocked by actual recalculation.
The other 18 operations, financial query projections and real budget integration remain pending. This
is not a frontend or deployment claim. Validation results are recorded in the feature PR.

## Follow-up: stored due states and no-change confirmation (Issue #95)

After #94 merged at 9bb9fa3, owner-scoped stored-due queries aggregate actual allocation principal,
interest and fee, preserve frozen due sources/loan status and evaluate all five current regular-due
states. #31 eligibility reuses that calculator under its existing user/due locks. New-key requests
that retain the current confirmation flag now skip recalculation/revision and return report_stale=false
(no additional invalidation), correcting the prior reconfirmation behavior to FINAL_DECISIONS.
The read model does not synthesize future dues, infer historical confirmation state or register an
incomplete LoanQueryPort. No new HTTP operation, shared port, schema or Team 2 code change. Six Team1
HTTP operations remain implemented; actual flag changes/registration still require the real budget
provider. Terms/balance replay, complete due coverage and monthly observation projections remain
needed before LoanQueryPort and actual budget/CSV/risk integration can be reported complete.
