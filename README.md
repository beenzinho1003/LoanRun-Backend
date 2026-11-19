# LoanRun Backend

Java 21 / Spring Boot 3.5.16 / Gradle Wrapper 8.14.7 / PostgreSQL 16.16 / Flyway / Spring Session JDBC.
One application rooted at `com.loanrun`. No login, JWT, account deletion or real payments.

## Run locally

Install JDK 21 and start Docker Desktop. Gradle is provided by the wrapper.

```powershell
docker compose up -d db
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

```sh
docker compose up -d db
./gradlew bootRun --args='--spring.profiles.active=local'
```

The local profile uses `jdbc:postgresql://localhost:5432/loanrun`, user `loanrun`, password `local-demo-only`.
The database port binds to `127.0.0.1` only. These defaults are for local synthetic demo data.
`.env.example` documents overrides. Docker Compose reads `.env`; `bootRun` does **not**.
If overriding the Compose password, export the corresponding `SPRING_DATASOURCE_*` variables in the application shell too.
Never commit `.env`, secrets, private handoff material or database volumes.

Health: `GET http://localhost:8080/actuator/health`.
Local API docs: `/swagger-ui/index.html` and `/v3/api-docs` (springdoc 2.10.0). Disabled outside `local`.

## Verify

```powershell
.\gradlew.bat clean build
```

```sh
./gradlew clean build
```

Tests require Docker and run against a separate PostgreSQL Testcontainer. They do not clear the local development DB.
Integration checks cover persistent anonymous cookies, browser isolation, session expiry, validation,
FREE/PRO transitions, exact routes, origin validation, row locking, idempotency and transactional rollback.
CI executes the same build without production secrets.

## HTTP/session contract

- Keep the final `/` in API URLs. JSON uses `snake_case`; numbers must be JSON numbers.
- Responses contain `success`, `code`, `message` and exactly one of `data` or `errors`.
- First supported API access creates an independent anonymous user. `LOANRUN_SESSION` is host-only,
  HttpOnly, SameSite=Lax, persistent for seven days; it is Secure in production. An expired session starts a new user.
- Use a same-origin `/api/` proxy (Vite in development, Nginx in production). Cross-origin clients must use
  `credentials: 'include'` and an exact configured origin; cross-site deployment needs separate validation.
- Mutating requests **must** carry an `Origin` in `ALLOWED_ORIGINS`, including curl/Postman requests.
  No extra CSRF endpoint or token is needed. Local defaults: `http://localhost:5173,http://localhost:8080`.
- A supplied disallowed `Origin` is rejected for every API method with a JSON `ORIGIN_NOT_ALLOWED` error.
  GET requests without an Origin remain supported. Profile `occupation` accepts an empty string or null; `name` must be nonblank.
- Request JSON is limited to 1 MiB. PATCH omission retains the value; explicit null is accepted only where documented.
- User IDs in request bodies cannot select an owner. PRO checks read server state on every request.
- `PATCH /api/settings/subscription/` accepts `{"plan_type":"PRO"}` or `FREE` only when `DEMO_MODE=true`.
  Repeated identical transitions do not advance revision. Billing is always disabled.

Loan preview: `POST /api/loans/preview/` validates the shared registration input and returns the next basic
payment, principal, interest and required BULLET saving using the real engine. It does not save a loan or
advance financial revision. Manual product names work independently; selecting a product requires the
Team 2 catalog port (503 until connected, missing product 404). Numeric strings and unknown fields are rejected.

Loan registration: `POST /api/loans/` uses the same validation and returns 201 when its required budget
recalculation provider is integrated. It atomically stores V2 loan sources, the default MATURITY snapshot and
first due, enforcing seven active loans under the existing user lock. Optional `Idempotency-Key` replays the
original response. Without the real recalculation provider it returns 503 and saves nothing. See
[registration transaction and migration notes](docs/LOAN_REGISTRATION.md) before applying V2.

Loan rate history: `GET /api/loans/{loan_id}/rate-history/` returns stored manual rates in application-date/ID
order, including the registration rate with an unknown (null) previous rate. Foreign, missing and deleted
loans return the same 404; paid-loan history remains visible. No financial sources are changed. See
[rate history behavior and tests](docs/LOAN_RATE_HISTORY.md). Rate change registration is still pending.

Payment detail: `GET /api/payments/{payment_id}/` returns the session owner's preserved actual payment,
including fee/savings-use components, every saved due allocation and snapshot-based editability.
History remains readable for PAID/DELETED loans. Missing/foreign records return the same 404; no actuals
are rewritten or inferred. See [payment detail semantics](docs/PAYMENT_DETAIL.md). Payment writes remain pending.

Payment list: `GET /api/payments/` accepts optional `loan_id`, `from`, `to`, `type`, `page`, `page_size`.
It uses inclusive actual-date filters, stable newest-first pages (default 1/20, maximum size 100), and
full-filter count/totals independent of the page. Items reuse payment detail, including all due allocations.
See [pagination, ownership and aggregate semantics](docs/PAYMENT_LIST.md).

Due confirmation: `PATCH /api/payments/schedule/{due_item_id}/confirmation/` accepts
`{"unpaid_confirmed":true}` or false with required `Idempotency-Key`. Only past unrecorded regular dues
can be confirmed/cancelled; actual payments and assets remain unchanged. The flag, budget recalculation,
revision and replay response commit together. Without the real recalculation provider it returns 503 and
saves nothing. See [confirmation states, locking and integration limits](docs/DUE_CONFIRMATION.md).

Current routes: POST `/api/loans/`, POST `/api/loans/preview/`, GET `/api/loans/{loan_id}/rate-history/`, GET/PATCH `/api/users/me/`, GET/PATCH `/api/settings/subscription/`,
GET/PATCH `/api/settings/notifications/`, GET `/api/settings/export/`, POST `/api/risks/simulate/`, GET `/api/payments/`, GET `/api/payments/{payment_id}/`, PATCH `/api/payments/schedule/{due_item_id}/confirmation/`.
Report routes also include GET/PATCH `/api/reports/comparison-settings/`, GET `/api/reports/latest/`,
GET `/api/reports/{report_id}/`, POST `/api/reports/` (persisted admission and after-commit calculation;
source ports require integration), and GET `/api/reports/strategy/` (stored snapshot and current candidate validation).
Current routes: GET/PATCH `/api/users/me/`, GET/PATCH `/api/settings/subscription/`,
GET/PATCH `/api/settings/notifications/`, GET `/api/settings/export/`, GET/PUT `/api/budgets/profile/`,
GET/PUT `/api/budgets/{year_month}/`, PUT `/api/budgets/{year_month}/allocation/`.
GET/PUT `/api/budgets/{year_month}/`, POST `/api/income-entries/`.
Further domain endpoints are tracked locally as pending until implemented and tested.

Dashboard consumer: GET `/api/dashboard/` composes selected ledger projections with household risk and
server-gated report status. The proposed source provider is still an integration dependency; see
[dashboard behavior and verification limits](docs/DASHBOARD.md).

Risk detail consumer: GET `/api/risks/` supports PRO household assessment and selected detail. Preserved
finance changes and frozen historical assessments still require persistence integration; see
[risk detail behavior and remaining sources](docs/RISK_DETAIL.md).

Export accepts `format=csv` and optional `start_month`/`end_month` in `YYYY-MM` form.
Only the session owner's loans and preserved payments are exported, including historical transactions for deleted loans.
Month filters apply to payments. The CSV uses UTF-8 BOM, CRLF, quoted cells and spreadsheet formula protection.
The original nine columns are followed by `fee_amount,savings_used_amount`, as required by the detailed export rules.
Success is a CSV attachment; errors use the JSON envelope. Team 1 supplies the owner-scoped `LoanQueryPort` projections;
until those implementations are integrated, export returns `503 SERVICE_UNAVAILABLE` without runtime placeholder data.

Risk simulation is PRO-only and accepts `scenario_type` (`CURRENT`, `RATE_UP`, `INCOME_DOWN`, `EXPENSE_UP`)
and optional positive `loan_ids`. These scenarios independently apply variable-rate +1 percentage point,
income -5%, or a one-time current-month expense of 500,000 won. Direct slider values are rejected. Loan IDs
validate an owner-scoped filter; household calculations still include every active loan. The read-only request
does not advance revision. Missing required query/engine implementations return 503; no runtime fixtures exist.
See [risk input and projection contracts](docs/RISK_INPUTS.md) for cash, target and uncertainty handling.

Comparison settings are PRO-only. GET returns a default non-consented DEMO configuration without creating
a database row. PATCH preserves omitted fields and accepts explicit null for age/income categories only when
the resulting state is non-consented. Consent requires an age group and a user-selected income group from
1 to 5 (`income_decile` remains the compatibility key). These are not real peer statistics. Withdrawal does not
delete personal reports. Actual setting changes alone advance revision and set `regeneration_required=true`;
no report or AI generation starts automatically. Updates lock the user row, recheck PRO and commit settings
with revision together. V5 application order must be coordinated before integrating these routes.

Report GETs read stored jobs only and require PRO. Latest returns NOT_GENERATED when absent and hides content
that is stale or not completed. Detail retains old reports with a stale flag and disabled strategy actions; other
owners' IDs return 404. Withdrawal hides comparison in preserved results. Dates describe the returned report's
last successful generation, not a pending status update. GET does not create jobs or invoke AI. The detailed
schemas currently declare monthly principal rate as string in latest and number in detail; endpoint adapters
preserve those types without modifying storage. See [report reads](docs/REPORT_READ.md).

Report POST captures server-owned input and deduplicates jobs by user, scope, revision and basis month. It returns
202 for new/pending/running work and 200 for a reused completed job. FAILED retries require `force=true` and a
60-second wait. Admissions share a per-user hourly limit across scopes, revisions and PRO changes; cached
responses consume no new attempt. Job/input/attempt commit together and processing listeners must use
AFTER_COMMIT. This branch implements admission only; no placeholder report or AI call is produced.
See [generation admission](docs/REPORT_GENERATION.md) for the remaining worker prerequisites.
Finance profile GET returns 404 before the first save. PUT validates whole-won amounts, the income type and payday,
and synchronizes the anonymous user's income type. Zero and unknown emergency funds remain distinct.
Profile changes update only current/future unconfirmed DEFAULT months, preserving past/INPUT/confirmed months
and each month's original discretionary limit. These two APIs work without Team 1 loan/engine implementations.

Monthly budget GET requires `YYYY-MM` and uses only the session owner's data. It combines saved monthly inputs
with Team 1's due/payment/saving and plan projections. Missing implementations return `503 SERVICE_UNAVAILABLE`.
Future months are forecasts without persisted future rows; unknown actuals remain null, including unobserved
payments/savings. Confirmed zero income or spending remains zero. Irregular planning uses three completed
observed months, including confirmed zero months, or the profile estimate when there is insufficient history.
The request may materialize elapsed DEFAULT snapshots and refresh derived settlements under the user lock;
it does not alter original inputs, long-term plans or revision. Internal budget queries do not write.
Past corrections recalculate settlements and carry only to the immediately following month.
Income recording is a separate feature change.

Monthly budget PUT accepts only the current KST month and requires all four expense amounts. Income entries
are preserved when omitted, replaced as a whole when supplied, and deleted with `[]`; explicit null is invalid.
Existing entry IDs must belong to this user and month. Entry types follow the monthly income type, dates must
be in the current month and no later than today, and amounts must be positive whole won. Saving confirms the
expenses while preserving the original discretionary limit, then recalculates settlements and advances revision
once. Failure in validation or dependent projections rolls back the entire change, including income replacement.

Allocation PUT saves a current-month override with a required `Idempotency-Key`. The server validates owned
active loans, repayment/saving types, grace periods, actual spending floors and the latest allocation capacity
under the user lock. Already-used money remains counted even if its loan is omitted. Unallocated cash is allowed
and is not automatically added to reserve. Original principal, long-term plan versions and strategy are unchanged.
Updated plan views come from Team 1's `PlanPresentationPort`; a missing implementation returns 503. Saving,
settlement, revision and the stored retry response commit together. FREE responses hide strategy and priority,
including replay of a response originally saved under PRO. Schema and shared-port prerequisites must be integrated
before opening this feature's central PR.
Income POST records one current-month entry with a required `Idempotency-Key` (1–128 characters).
An identical retry returns the original 201 response and ID without another revision; a different body with
the same key returns `409 INCOME_ENTRY_CONFLICT`. Keys are scoped to the session owner and route. Concurrent
retries serialize under the user lock. Income, confirmation, settlement, revision and the saved response commit
together, so failed requests can safely retry the same key. Entry types/dates/amounts follow the monthly PUT rules.

## Shared development

[Technical contracts](docs/CONTRACT.md) define domain ownership, ports and migration reservations.
Team 1 implements the repayment engine and loans/payments/savings/plans. Team 2 owns common infrastructure
and user/budget/catalog/dashboard/risk/report/settings. Runtime mock implementations are prohibited.

The owner-scoped V2 source read repository in `loan/read` preserves original loan/history/ledger facts.
See [source query semantics and projection limits](docs/LOAN_SOURCE_QUERIES.md). It does not yet implement
LoanQueryPort or enable real budget/CSV/risk integration.

The real, pure `RepaymentEngine` implementation lives in `finance/engine`; Spring wiring is in `finance/config`.
See [calculation behavior and limits](docs/REPAYMENT_ENGINE.md) and the
[implementation audit](docs/IMPLEMENTATION_STATUS.md). The engine adds no HTTP routes or migrations.
Loan/ledger/plan query implementations are still required before the CSV and risk APIs can use actual domain data.

Production must set `SPRING_PROFILES_ACTIVE=prod`, database credentials and exact `ALLOWED_ORIGINS`.
Use an HTTPS reverse proxy and a private DB network; this local Compose file is not a production deployment.
No cloud resources or automatic deployment have been created by this bootstrap.

Version references: [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[Gradle 8.14.7](https://docs.gradle.org/8.14.7/release-notes.html),
[PostgreSQL releases](https://www.postgresql.org/docs/release/).
