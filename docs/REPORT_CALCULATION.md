# Report calculation worker integration

ReportCalculationAdapter connects the existing after-commit worker to captured personal statistics, four
independent risk/payoff scenarios, strategy inputs/results and the explanation-only client. It performs no live
budget/source query and rejects execution in a financial transaction. Team 1's actual engine supplies schedules,
fees and payoff results; no replacement engine or successful runtime fixture is installed.

Captured current-month overrides are passed to scenario projections and strategy inputs. Future goals remain
separate. The original household facts drive risk; an individual report uses its preserved actual history,
selected loan payoff date and reserved-funds strategy scope. Missing observations stay null, and confirmed
zero remains zero. The latest/detail rate types retain their existing endpoint-specific adapters.

Comparison requires captured consent. Its explicitly virtual DEMO peer examples (count 3, DSR 25) and
disclaimer never fill missing personal metrics. Current consent and PRO are rechecked on read. Explanation
failure/disabled configuration retains fixed wording and server numbers/strategy; core calculation failure
records FAILED without a financial result. No real OpenAI/FSS request was used in validation.

Completion still uses the short lease-protected store transaction, which stamps authoritative report ID/status,
KST times and dynamic stale state. Input changes during calculation do not publish a current result or reveal
withdrawn comparison data. No calculation, external call or long-running user lock is held inside that phase.

PostgreSQL/HTTP tests use the real Team 1 engine and mock only not-yet-integrated source query ports.
They check golden zero-rate schedules, scope, snapshots, null/zero, validated model text without numeric
changes, core failure and concurrent consent/stale behavior. These tests are not proof of the real ledger/query
chain, FE integration, live API account or deployment. V5/shared-port prerequisites remain pending.
