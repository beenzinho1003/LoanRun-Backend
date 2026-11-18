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

Current routes: GET/PATCH `/api/users/me/`, GET/PATCH `/api/settings/subscription/`,
GET/PATCH `/api/settings/notifications/`, GET `/api/settings/export/`, POST `/api/risks/simulate/`,
GET/PATCH `/api/reports/comparison-settings/`.
Further domain endpoints are tracked locally as pending until implemented and tested.

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

## Shared development

[Technical contracts](docs/CONTRACT.md) define domain ownership, ports and migration reservations.
Team 1 implements the repayment engine and loans/payments/savings/plans. Team 2 owns common infrastructure
and user/budget/catalog/dashboard/risk/report/settings. Runtime mock implementations are prohibited.

Production must set `SPRING_PROFILES_ACTIVE=prod`, database credentials and exact `ALLOWED_ORIGINS`.
Use an HTTPS reverse proxy and a private DB network; this local Compose file is not a production deployment.
No cloud resources or automatic deployment have been created by this bootstrap.

Version references: [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[Gradle 8.14.7](https://docs.gradle.org/8.14.7/release-notes.html),
[PostgreSQL releases](https://www.postgresql.org/docs/release/).
