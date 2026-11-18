# Strategy calculation adapter

The adapter consumes captured ReportInput only, prepares existing/candidate inputs, and invokes the shared
Team 1 RepaymentEngine. It does not implement loan interest, fees, principal caps or dynamic redistribution.
All cost/date results originate in that engine. No DB, current budget read, HTTP or AI decision is involved.

The agreed recommendation/cost policies operate on those results. Missing engines or empty numeric result
rows fail explicitly. Current-unfunded/engine-infeasible candidates cannot be recommended/applied. Stored
candidate simulation metadata uses the same global NET/GROSS basis and fixed warnings as the API view;
amounts/rows are retained. Versions include report ID, revision and basis month.

The user's individual scope decision is applied before engine input creation, including other-loan reserves
and captured current overrides. Household risk facts are supplied separately as RateContext. Individual
plan-performance figures and applied timestamps absent from the shared port stay null; household values
are never substituted. Single-loan current preferences come from its plan, not an unrelated overall strategy.

monthly_burden aggregates cash in the first simulated month at/after the captured asOf date. It adds engine
scheduled principal/interest, extraCash (already fee-inclusive) and saving deposits, subtracting saving reused
for that scheduled payment according to the engine's opening/closing saving balances. Previously saved
principal is not new cash. Invalid saving movements or foreign engine rows fail, without inventing zero cost.

Tests use a mock engine only. Passing them proves adapter boundaries/aggregation, not the missing real
Team 1 engine's calculations or frontend integration. The full report worker result assembler remains separate.