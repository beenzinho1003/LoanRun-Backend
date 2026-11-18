# Repayment engine

`DefaultRepaymentEngine` implements the existing `RepaymentEngine` contract without changing `FinanceModels`.
The calculator depends only on Java and immutable contract records. `RepaymentEngineConfiguration` registers
one production bean; no persistence, session, HTTP client, system clock or runtime fixtures are involved.
Every invocation owns its mutable working state, so simulations can run concurrently against the same bean.

## Schedules and money

- Annual rates are percentage values: 6 means 6%. Monthly interest uses annual rate / 1200.
  Calculations use DECIMAL128 and monetary HALF_UP rounding; required saving uses CEILING.
- EQUAL_PAYMENT uses the annuity formula, including the zero-rate branch. EQUAL_PRINCIPAL uses a fixed
  principal installment. The last installment settles the exact remaining principal without overpayment.
- GRACE_INSTALLMENT charges interest until the first installment on or after graceEnd, then uses the
  specified post-grace method. Planned extra payments during grace are withheld with a warning.
- BULLET charges interest on outstanding principal until contractual maturity. Saving does not reduce
  principal or interest. The final contractual principal obligation is shown even when underfunded;
  that result has insufficient=true and projectedPayoffDate=null. A zero final contractual balance
  must therefore not be interpreted as a funded payoff without also checking the result status.
- supplied nextDueDate anchors the schedule. Following months clamp the original dueDay, restoring it
  after short months. The final monthly installment uses the explicit contractual maturity date.
  horizonMonths counts calendar months starting with the asOf month, rather than the number of rows.
- Terms must describe outstanding principal at asOf. Past due dates are skipped without inventing
  payments; the engine does not reconstruct arrears or replay actual ledger records. Query/ledger providers
  must supply those facts. asOf cannot precede the source snapshot.
- contractualNextAmount applies only to the supplied next due date. Later rows use the independent base
  anchor. KEEP_PAYMENT preserves the payment/principal anchor after extra repayment. KEEP_MATURITY
  recalculates it for remaining installments after extra repayment.
- rateOverrides are absolute annual rates for this invocation. A changed rate reprices an annuity and
  supersedes the old next-payment amount for the scenario. Equal-principal anchors remain unchanged.
  The caller decides which loans receive a shock; original terms and fixed-rate loans are not mutated.

## Fees and cash

For confirmed MORTGAGE/JEONSE contracts, FIXED uses extra principal times fee rate; LINEAR also multiplies
by remaining/total days, capped at [0,1]. Fixed fees apply through the end date; after it they are zero.
Linear fees reach zero on the end date. NONE is confirmed zero. Unknown or unsupported conditions remain null.
Invalid confirmed fee rates/periods fail instead of becoming zero.

For known fees, each extra cash budget includes its fee. Binary search finds the maximum whole-won principal
that fits, capped by remaining principal. Repeated allocation passes round the period's aggregate fee once.
Interest is calculated before basic principal and extra principal, so an extra payment changes subsequent interest.

Unknown-fee projections use the requested principal component for GROSS_INTEREST comparison, with FEE_UNKNOWN,
null fee and null totalCash. extraCash in those rows is the gross modeled component, not a certified all-in bank
payment. Consumers must retain this uncertainty and cannot claim a known fee-inclusive cash requirement or net saving.
Even a baseline with no extra payment retains unknown fee conditions for consistent candidate comparisons.

totalCash sums future basic cash, extra cash and saving additions, subtracting the principal funded from existing
loan-specific savings at maturity. New saving outflows are counted once, and the initial asset is not a new contribution.
Totals describe the requested horizon; HORIZON_EXHAUSTED indicates an incomplete projection without asserting a
funding failure. An absent payoff date is not replaced by an arbitrary date.

## Allocation and inverse targets

KEEP_PLANS, manual amounts, and single-loan preferences preserve explicit per-loan conditions. A null priority
also pins a manual amount within a strategy month, including explicit zero. Unused manual caps are not transferred.

RATE_RISK and INTEREST_SAVING pool only the supplied priority-bearing extra cash and follow the supplied order.
PARALLEL recomputes weights from balances after basic payments each month, assigning whole-won remainders by ID
and redistributing unused principal caps. Paid loans' existing extra budgets can flow to remaining eligible targets;
their former scheduled payments are never added to that budget. BULLET saving is separate from direct extra cash.
Affordability, required-saving reservation, plan effectiveness and current actual deductions belong to the caller's
captured input policy. The engine does not read a budget or invent household funding.

minimumExtra replaces the selected loan's old extra goal and finds the minimum total recurring extra cash needed
by the target, including known fees. It tests the target with the same simulation and returns an explicit infeasible
result for dates outside the contract, no eligible installments, or an insufficient horizon.
For BULLET it returns the minimum saving contribution to have funds ready by the target: ceil(shortage / remaining
contribution dates). With no dates it returns the immediate shortage and feasible=false unless already funded.
This readiness result is distinct from a recorded or assumed early repayment. SimulationInput.targetDate evaluates
the goal; it does not authorize an automatic early BULLET principal payment. HTTP plan adapters must preserve that
distinction when presenting targets versus projections; multi-loan individual target policies remain adapter work.

## Validation

Tests use synthetic data and a fixed KST reference date. Independent expectations include 12M KRW / 6% / 12 months:
annuity 1,032,797; equal-principal total interest 390,000; BULLET interest 720,000. They cover zero rate, final one won,
grace boundaries, leap dates, exact maturity, next-payment scope, both prepayment policies, fee rounding/null, inverse
minimality, savings accounting, manual zero, dynamic allocation and concurrent repeatability.

EngineAdapterIntegrationTest supplies the real engine to the unchanged Team 2 RiskRateStress,
RiskPayoffProjection and StrategyEngineInputs. This verifies bean wiring and adapter arithmetic without a mock engine.
It does not establish completed loan/ledger/plan APIs, actual external FSS/OpenAI calls, frontend integration or deployment.
