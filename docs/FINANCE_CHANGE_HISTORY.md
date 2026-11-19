# Finance mutation journal hooks

Runtime recording is disabled by default (`RISK_HISTORY_ENABLED=false`). Enable it only after the reviewed
risk-history SQL receives an agreed Flyway version and is applied with the budget schema. Disabled history
does not register a successful empty risk-history provider.

Profile PUT, current-month budget PUT and income-entry POST capture own-budget planned income/expense
amounts before and after the mutation. The journal uses the financial mutation's revision under the same
user lock/transaction. Initial profile creation is a baseline; identical amounts do not produce a change.
Confirmed INPUT months remain unchanged when profile defaults change. An income idempotency replay
does not execute the mutation or append another history event.

Capture uses only Team 2 budget tables, with no loan engine or cyclic BudgetQueryPort call. Regular planned
income is expected income plus recorded entries; irregular planned income uses the same three observed
completed-month average/fallback rule as budget reads. Expenses include required/selected expenses and
general saving, consistently with planning cash B. This is a record of planned cash input changes, not an
invented historical risk score or an assertion of actual received income. The original income entries remain.

Failures in journal append roll back entries/expenses/revision/idempotency together. Existing history is never
updated/deleted. The proposal SQL is applied only to isolated test databases until migration coordination.

Verification: finance-history HTTP tests (3) plus existing profile/monthly-write/income tests (14), 17 passed.
Ledger/plan inputs are test-only mocks. This does not establish live source or deployment completion.
