# Selected-loan strategy scope

The user confirmed on 2026-11-18: an individual report generates candidates for that loan alone, after
protecting other loans' existing goal funds. Household reports keep their original scope.

The pure selector reserves other ACTIVE loans' future goals for recurring capacity, and protects their
remaining current-month goals/overrides separately. Required BULLET saving takes priority even if its
configured saving or current override is lower; an unknown mandatory plan defers candidate generation.
Future grace goals reserve recurring funds without creating ineligible extra cash in the current month.

Other already-used cash, including overspending and payments for loans absent from the active list, is
protected alongside remaining goals. The selected actual is deducted exactly once by the engine-input
builder. Unknown capacity fields stay null. Original terms, plans, history and overrides are not mutated.

The result is candidate-only: finance/profile and due context remain household facts, while candidate terms,
plans and cash capacity are selected. Never use this transformed input to replace household risk assessment
or derive individual observed history. That history remains the owner-scoped ReportHistoryQueryPort output.
