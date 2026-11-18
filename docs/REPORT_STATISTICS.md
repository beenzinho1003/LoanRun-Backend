# Personal observed-history aggregation

`ReportStatistics` is a pure internal calculation over Team 1's `HistoryMonth` and confirmed monthly incomes.
It selects observed months in the six completed calendar months before the supplied basis month. Gaps and
unobserved months are excluded, not converted to zero. Duplicate observed month rows are rejected to avoid
double-counting. No active-loan filter removes preserved payments for deleted/paid loans from supplied history.

Monthly principal rates use regular + extra actual principal divided by opening + newly registered balance,
then average the eligible monthly ratios. Period principal reduction uses actual principal divided by the first
observed opening balance plus registered balances. Debt service uses principal + interest divided by actual
income, excluding fees and separate saving deposits. Saving deposits/uses and extra-payment counts remain
separate aggregates. Positive-denominator zero payments are known zero; missing income/zero denominators
remain null. Empty history has unknown totals, not invented zeros. Ratios round HALF_UP at final display precision.

The returned BigDecimals are internal statistics, not HTTP DTOs. Report adapters must retain each detailed API
field's declared JSON type when exposing them. This change adds no report API, worker, schema, peer demo
average or runtime fixture and performs no amortization calculations.
