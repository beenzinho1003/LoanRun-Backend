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
