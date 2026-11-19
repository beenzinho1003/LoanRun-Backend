# Completed-month assessment maintenance

Budget recalculation publishes the owned settled months after updating the affected settlement chain. Existing
settled months are included so a later lazy budget read can retry a previously unavailable derived result.

1. Before the financial commit, record each month as pending with the final owner revision. Rollback removes
   this marker together with the mutation. A pending month cannot expose an older frozen result.
2. After commit, suspend financial transaction resources and capture the exact historical month-end loan,
   plan, budget, payment, saving and recorded-history inputs in a short repeatable-read transaction.
3. Validate confirmed source status and source ownership, then run the actual Team 1 engine outside the DB
   transaction. Calculate household metrics once and copy them into owned selected-detail presentations.
4. In a separate short write, lock the owner and recheck the captured revision. Append an immutable version
   and clear its request marker atomically. Changed inputs discard the late result; old versions remain stored.

Unconfirmed/current months never become frozen past assessments. Failed evaluation does not pretend the
already committed financial transaction rolled back: its request remains pending and API #43 returns 503.
A later settlement read retries it. This is a lazy retry path, not an external scheduler or a guarantee of
background execution without another request. Logs use fixed messages without owner/financial payloads.

Six integration tests use real budget settlement, historical capture, PostgreSQL and the actual Team 1 engine
with test-only ledger/plan/event input ports. They verify after-commit execution, month-end engine basis,
transaction suspension, rollback, failed-source retry, invalidation of older results, changed revisions and
rejection of unconfirmed/foreign sources. Actual source adapters and migration coordination remain separate.
