# Backend technical contract v1

Root package `com.loanrun`, Java 21, Spring Boot 3.5.16, Gradle 8.14.7, PostgreSQL 16.16.
Business dates use the injected `Clock` in Asia/Seoul; persisted event timestamps are UTC `Instant`.
API amounts are `Long` KRW; calculations use `BigDecimal` with HALF_UP (required savings use ceiling).

## Ownership and boundaries

Team 1: `loan`, `finance`, `payment`, `repaymentsaving`, `repaymentplan`.
Team 2: `common`, `user`, `onboarding`, `loanproduct`, `budget`, `dashboard`, `risk`, `report`, `setting`, root/infra.
Controllers call services; cross-domain access uses `common.contract` ports, never another domain's repository or HTTP self-calls.

`FinanceModels` contains immutable internal projections, separate from public response DTOs and persistence entities.
The initial port signatures are the bootstrap integration proposal; confirm fields with the other owner before changing shared files.

| Port | Provider | Consumer |
|---|---|---|
| `CurrentUserProvider` | Team 2 | HTTP entry points only |
| `UserMutationLock`, `RevisionPort` | Team 2 | Both teams |
| `LoanQueryPort` | Team 1 | Budget, onboarding, risk, report, home, CSV |
| `PlanQueryPort` | Team 1 | Budget, onboarding, risk, report, home |
| `RepaymentEngine` | Team 1 | Plans, risk, strategy |
| `BudgetQueryPort`, `BudgetRecalculationPort` | Team 2 | Plans and ledger mutations |
| `ProductQueryPort` | Team 2 | Optional catalog product validation |
| `StrategyQueryPort` | Team 2 | Strategy preview/save |
| `FinanceProfileQueryPort` | Team 2 | Profile income-type consistency |
| `UserAccessPort` | Team 2 | Internal/async plan checks without HTTP request scope |
| `PlanPresentationPort` | Team 1 (proposal) | Month allocation responses with caller-supplied overrides |

The two new ports are a separate review proposal. `UserAccessPort` reloads server plan state per check;
it does not expose a public user selector. `CurrentUserProvider` resolves the HTTP session and delegates to it.
`PlanPresentationPort.monthViews(userId,asOf,month,overrides)` must read saved plans and apply the supplied monthly view
without re-entering budget capacity/mutations. `PlanView` contains nullable forecast/actual fields and non-null warnings.
No production replacement implementation is supplied. Confirm the projection fields/units with Team 1 before merging consumers.

The engine is pure. No replacement engine or production empty-data stub is supplied.
All loan queries are scoped by `userId`. A foreign or missing loan produces `404 LOAN_NOT_FOUND`.
`PaymentSummary.cashPaid` excludes savings used. `DueSummary.scheduledCash` is the current month's full contractual cash burden,
and `previousOutstandingCash` excludes arrears already paid this month. Do not subtract paid scheduled cash a second time.
`extraCashByLoan` excludes savings-funded principal and includes the applicable fee. Unknown actuals remain null/unobserved.

## Transactions

Resolve the current user **before** opening the domain transaction. Internal services take explicit `userId`.
For every financial mutation: begin transaction → `UserMutationLock.lock(userId)` → idempotency lookup → write/replay
→ budget recalculation from the earliest impacted month → one `RevisionPort.bump` → persist response → commit.
`lock`, `bump`, idempotency and recalculation require the caller transaction. Repeated idempotent responses do not bump revision.
The idempotency key scope is `(user, method, exact path, key)`. Object field order is canonicalized; array order and explicit null are preserved.
Use each API's conflict code. External FSS/OpenAI requests run outside user locks and financial transactions.

Do not create constructor dependency cycles between budget and plans. Capacity reads due/payment/savings projections;
plan saves consume capacity; ledger changes call recalculation without calling another ledger mutation.

## Migration reservations

| Version | Owner | Purpose |
|---|---|---|
| V1 | Team 2 | Demo users, JDBC sessions, idempotency, per-user notification defaults |
| V2 | Team 1 | Loans, balance/rate history, due/payment/savings ledger |
| V3 | Team 2 | Finance profiles, monthly budgets, catalog |
| V4 | Team 1 | Individual plans and overall strategy versions |
| V5 | Team 2 | Report jobs, strategy candidates, comparison settings |

Only V1 is part of bootstrap. Notification defaults are included in V1 so those APIs can be tested without applying V5 ahead of V2–V4.
The separate V3 schema change prepares the six finance/budget/catalog tables without product API implementations.
`monthly_allocations.loan_id` remains a scalar until Team 1's loan schema and a subsequent FK migration are coordinated.
V3 was already shared in the closed combined draft; this split preserves its original content and checksum.
Keep the schema PR in Draft until Team 1's V2 application order is confirmed. Fresh tests can apply V1/V3;
a development DB that has applied V3 must coordinate a one-time Flyway out-of-order run when V2 arrives.
Do not enable out-of-order globally in production. Validate the complete migration sequence before deployment.
Coordinate migration ordering before merging later stages. Do not create empty placeholder migrations for another owner.
Never edit a migration after sharing/applying it. Hibernate validates; Flyway creates schemas. No H2 substitution or ddl-auto=create.

`demo_users.id` is BIGINT and is the ownership FK target. Lock this row before mutations. `revision` is a data counter under this lock,
not a JPA `@Version`; increment once per original data change. Preview/report freshness must also include business date/month.
