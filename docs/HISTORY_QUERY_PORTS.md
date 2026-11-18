# History query proposal — Team 1 review required

These interfaces are proposed read contracts for Team 2 risk/report consumers. They add no implementation,
runtime fixture, DB table, endpoint or repayment calculation. Keep this change Draft until Team 1 agrees on
the source/units and implementation order; compilation alone is not contract approval.

`LoanEventQueryPort.rateChanges(userId, range)` returns only that owner's actual manually recorded rate
changes in the inclusive date range. Preserve events for paid/deleted loans. `rateChangePp` is a signed
percentage-point change (6% to 7% is +1, not +0.01); `monthlyBurdenDelta` is signed whole won or null when
the cash effect is unknown. Never infer observed rate history from hypothetical scenarios or current rates.
Sort order is not required; callers may sort immutable snapshots before display.

`PlanPerformanceQueryPort.performance(userId, asOf)` uses the owner's historical plan versions and actual
per-loan observations, not only today's plan. `adherenceRate3m` is percent for three completed months:
sum of each observed loan/month's min(actual, target) divided by total target, times 100. Required history or
a positive denominator missing means null. `consecutiveExtraOnTrackMonths` counts confirmed consecutive
completed months ending at the most recent completed month, stopping at a missed/unknown month; missing
history must not become a fabricated zero streak. `missedPaymentCount6m` covers confirmed required-payment
failures in the last six completed months; unrecorded months are not automatically missed payments. Unknown
counts remain null; a confirmed zero remains zero. Report use must not expose PRO-only data to FREE callers.

`ReportHistoryQueryPort.history(userId, loanId, range)` supplies actual monthly history for the requested report
scope. A null loan ID means the household; a specific ID must be owned, otherwise LOAN_NOT_FOUND. Preserve paid
and deleted loans' actual payments in the appropriate scope. Empty/unobserved history must not become invented
zero-month rows. Team 2 cannot derive a specific loan's history from aggregated household rows. The existing
`LoanQueryPort.history(userId, range)` signature stays unchanged; consumers must not silently use it for a loan scope.

Review request: confirm ownership filtering, preservation after deletion, source plan version/override selection,
window boundaries, sufficient-history rules and null semantics before implementing these projections.
