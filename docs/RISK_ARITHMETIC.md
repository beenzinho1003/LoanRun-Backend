# Risk arithmetic

`RiskMath` implements the project's demonstration indicators, not regulatory DSR, credit scoring or a repayment engine.
Loan cash burdens and stressed burdens are supplied by the owning engine/read projections.
This change has no runtime service, database writes or new HTTP endpoints.

The four indicator weights are surplus 40%, basic debt service 30%, discretionary usage 15% and variable-rate stress 15%.
Levels contribute 0/50/100 points. Overall thresholds are below 25 stable, below 60 caution, otherwise danger.
Known basic inability overrides incomplete data; missing inputs otherwise remain unknown. Target/stressed basic inability
raises a known stable result to at least caution. No-loan input does not manufacture a risk result.

Classification uses unrounded ratios; displayed percentages alone use HALF_UP to two decimals.
Zero discretionary limit with zero use is stable, with positive use danger, while a missing use remains unknown.
The binary threshold search requires a monotonic predicate and returns the first strict failure, zero if already failing,
or null if the configured range never fails. Expense thresholds use exact integer arithmetic with overflow protection.

Pure tests cover open/closed boundaries, misleading display rounding, zero/missing values, weighting, overrides and first failure.
HTTP ownership/PRO, historical events, scenario inputs and real engine integration belong to the subsequent risk feature.
