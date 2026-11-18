# After-commit report worker

Admission events are dispatched only AFTER_COMMIT to a report-specific bounded executor (one core thread,
maximum two threads, queue capacity sixteen). It does not replace the application's general executor. A rejected
request is recorded as REPORT_QUEUE_FULL with the existing retry delay; no financial result is invented.

Each task claims a persisted job once, checks current PRO and captured user/scope/revision/month consistency,
then calculates outside financial DB transactions. Completion/failure uses the short, lease-protected store phases.
Worker logs contain exception class names only. No captured payload, upstream message or credential is logged.
Missing ReportCalculation implementations fail explicitly with SERVICE_UNAVAILABLE and no result payload.

`loanrun.reports.worker-enabled=false` disables automatic dispatch for admission-only tests/development. The
calculation adapter is described in REPORT_CALCULATION.md; this worker is not a placeholder adapter
and does not itself call OpenAI or calculate loan schedules. Report #45 is not fully integrated until those remaining
parts are wired and tested. Source contracts/V5 remain pending, so this prepared branch is not a cumulative PR.

Tests disable background processing by default and explicitly enable it in worker/recovery integration tests.
This prevents unrelated cached Spring contexts from scanning containers already closed by other test classes.

Stranded PENDING/RUNNING jobs are checked once per minute after startup and fail after ten minutes without
an update (both intervals are configurable). Recovery scans at most 100 rows and rechecks observed state/time
under the user lock in separate short transactions. A new claim or completion is preserved. Recovery does not
create reports or retry them; it records REPORT_GENERATION_TIMEOUT and the normal 60-second retry time.
