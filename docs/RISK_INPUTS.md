# Risk input assembly

`RiskInputService` assembles internal risk facts through the existing owner-scoped loan, plan and budget
query ports. It introduces no HTTP routes, schema or engine implementation. HTTP callers must check PRO and
validate selected loan IDs separately; the snapshot always describes the whole household.

Queries use one user/month in a read-only repeatable-read transaction. Past months use month end for both
loan and plan projections; current inputs use the injected KST Clock. When called inside another transaction,
the caller must retain that isolation. Historical correctness also requires the query-port implementations to
honor the supplied date; this adapter does not manufacture historical snapshots.

Recurring targets include installment extra cash and the greater of planned/required BULLET savings.
Grace-period extra targets begin on the grace end date. Unconfigured plans or unknown grace dates make
target totals unknown, while an empty active-loan set has known zero targets. Only active-loan plans count.
Variable exposure uses outstanding variable-rate principal divided by all active outstanding principal;
a zero denominator remains null. Original nullable actual income and budget facts are preserved.

The adapter performs no interest, schedule or payoff calculation and does not call an engine or an external
service. Missing query implementations fail with `503 SERVICE_UNAVAILABLE`; startup does not install runtime
fixtures. Immutable snapshots are inputs for subsequent risk/scenario API changes, not completed APIs.

`RiskRateStress` creates a request-local probe that asks Team 1's engine for a one-month basic schedule with
no extra/saving targets. It changes only variable annual rates by percentage points and compares basic burdens
against the engine's zero-change result. Only rows in the requested month count. Each distinct increase is
evaluated once per probe; no cache is shared across snapshots. Strict target/basic failure thresholds use the
specified 0–5 percentage-point range and 0.01 grid, based on cash before carry. Fixed-rate-only inputs have no
rate threshold; missing baseline cash remains null. An absent engine never becomes an invented zero impact.

`RiskPayoffProjection` forwards caller-supplied monthly amounts and rate overrides to the engine. Callers must
prepare remaining current-month amounts after actual usage, future recurring targets and applicable dates;
the projection adapter does not rebuild them or calculate loan schedules. Its feasibility classification compares
each active loan's engine payoff date with that loan's saved target. Meeting only the latest household target
cannot hide an earlier missed loan target. Explicit engine funding failure is LOW, a known missed target is
MEDIUM, every target must be known and met for HIGH, and incomplete dates remain null. Engine warnings remain
attached to the projection. Cash/required-saving feasibility and the HTTP scenario API are separate changes.

## Fixed scenario API

`POST /api/risks/simulate/` requires PRO. CURRENT, RATE_UP (+1%p for variable loans), INCOME_DOWN (-5%) and
EXPENSE_UP (500,000 won in the current month) each start from the same unchanged snapshot. Request `loan_ids`
cannot narrow household cash or debt. The service retains read-only REPEATABLE_READ across input and actual
ledger queries, and performs no original writes or revision changes.

Monthly targets passed to the engine subtract recorded current-month extra cash/fees or saving additions;
future months keep saved targets. Required BULLET savings are used for risk/affordability checks, not silently
written into a larger user goal. Whole months before plan effectiveness/grace end have no extra target; the engine
owns exact dated grace/payment behavior. Missing target or cash facts remain unknown. Target/basic failure
thresholds use pre-carry cash, with 0.01% income steps and one-won expense steps.

Emergency coverage divides known emergency funds by recurring shortfall only. A one-time expense shortage is
separate; it does not become a fictitious monthly deficit. Engine projection warnings are retained. API tests
use controlled query/engine fixtures for adapter behavior; actual Team 1 financial-engine integration is pending.

`RiskSimulationService.capture` includes the payment/saving ledgers in an immutable `RiskScenarioInput` under
the same read snapshot. `evaluate` uses only that captured input and the pure engine; it performs no session or
financial persistence reads. It also returns known payoff dates for later report composition while the existing
HTTP response remains unchanged. Internal callers must authorize the user/job before capture and check stored
revision/month before publishing a result. This separation does not start a worker or an external API call.
