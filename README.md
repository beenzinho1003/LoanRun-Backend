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
GET/PATCH `/api/settings/notifications/`, GET `/api/settings/export/`, GET/PUT `/api/budgets/profile/`,
GET `/api/budgets/{year_month}/`.
Further domain endpoints are tracked locally as pending until implemented and tested.

Export accepts `format=csv` and optional `start_month`/`end_month` in `YYYY-MM` form.
Only the session owner's loans and preserved payments are exported, including historical transactions for deleted loans.
Month filters apply to payments. The CSV uses UTF-8 BOM, CRLF, quoted cells and spreadsheet formula protection.
The original nine columns are followed by `fee_amount,savings_used_amount`, as required by the detailed export rules.
Success is a CSV attachment; errors use the JSON envelope. Team 1 supplies the owner-scoped `LoanQueryPort` projections;
until those implementations are integrated, export returns `503 SERVICE_UNAVAILABLE` without runtime placeholder data.

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
Past corrections recalculate settlements and carry only to the immediately following month. Monthly editing,
income recording and allocation editing are separate feature changes.

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
