# Immutable finance input versions

`finance_input_versions` preserves the exact previous/next own-domain profile, monthly-budget and income
entry records under the financial mutation revision and origin. The journal includes entry identifiers/dates,
individual amounts, null values and future profile defaults. It does not include another owner's data or any
loan/payment ledger dump. DB update/delete triggers protect saved input versions.

This is separate from chart-facing cash changes. Changing an entry date with an unchanged total retains the
old/new input versions without inventing an income change. Editing future profile defaults while the current
INPUT budget stays fixed preserves the profile change without assigning it a false current-month cash effect.
Initial creation retains a baseline input version with null previous input, rather than a fabricated zero baseline.

Both input versions and cash-change records append in the same transaction as expenses/entries/revision and
idempotency. A journal failure rolls back all of them. Replaying a successful income key appends neither.
Same revision/origin with different source JSON is a conflict; JSON object key order alone is not a conflict.

Five finance-history HTTP tests and eight repository tests cover these rules, including same-total date edits,
future default preservation, null/zero, owner isolation, immutable rows and rollback/retry. The SQL proposal
still requires migration-version coordination and activation before use outside isolated test databases.
