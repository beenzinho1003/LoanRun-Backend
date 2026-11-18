# Strategy recommendation policy

The pure policy consumes engine costs and captured facts; it does not calculate loan schedules, interest,
fees, allocation or adherence. It does not call AI, read live data or expose an endpoint.

All candidates use NET comparison only when baseline and every candidate fee are known. Otherwise every
candidate uses GROSS_INTEREST and every net saving stays null. Negative savings are retained. Missing
comparable interest defers cost ranking; arithmetic overflow fails instead of changing a result.

For feasible multi-loan candidates, the agreed variable-rate risk override takes precedence at 50% exposure
when the +1pp scenario cannot maintain the existing plan and a variable-rate direct repayment target exists.
Required basic/saving funding must be verified by the caller. Other rankings use savings, then the applied
candidate, then INTEREST_SAVING, RATE_RISK, PARALLEL. No input list ordering affects ties.

Single-loan preferences compare exact unrounded 3%, 8% and 90% thresholds. Missing inputs are preserved;
they do not become zero expenses. The report adapter must also check feasibility before displaying a
recommendation. Candidate generation, stored versions, API #50 and report calculation remain separate work.
