# Report job persistence phases

The repository and store are infrastructure for later report APIs/workers. They start no worker, scheduler,
engine or external call. V5 must be coordinated and merged first; keep this feature on its prepared fork branch.

Creation/requeue requires a caller transaction and the user mutation lock, including optional loan ownership
validation before creation. Worker `start`, `complete` and `fail` each use a short REQUIRES_NEW transaction and
the same user lock. Invoke them outside an enclosing transaction that already holds that user's lock. Compute
results after `start` commits; do not run engine/AI work in these DB phases.

Only one PENDING claim becomes RUNNING. A lease includes the DB-read claim timestamp, advanced monotonically
at PostgreSQL microsecond precision even if the wall clock repeats or moves backwards. Late completion/failure
cannot overwrite a failed/requeued execution with a new timestamp. A failed job cannot requeue before 60 seconds.
Result metadata is server-derived; completion compares source revision/month under the user lock and marks
changed inputs stale. Result and strategy candidates commit together, and a candidate failure rolls everything
back. Candidate report/revision must match the claimed job. State updates do not advance financial revision.

HTTP readers must recheck PRO/ownership and current revision/month before displaying a result, including later
changes after completion. Worker callers must validate job/user access before computation. Recovery/dispatch,
rate limits and full report payload construction belong to separate changes; this storage module implements
none of those APIs. Error codes are fixed identifiers, never upstream exception messages or credentials.
