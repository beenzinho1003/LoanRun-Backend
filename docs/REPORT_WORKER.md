# After-commit report worker

Admission events are dispatched only AFTER_COMMIT to a report-specific bounded executor (one core thread,
maximum two threads, queue capacity sixteen). It does not replace the application's general executor. A rejected
request is recorded as REPORT_QUEUE_FULL with the existing retry delay; no financial result is invented.

Each task claims a persisted job once, checks current PRO and captured user/scope/revision/month consistency,
then calculates outside financial DB transactions. Completion/failure uses the short, lease-protected store phases.
Worker logs contain exception class names only. No captured payload, upstream message or credential is logged.
Missing ReportCalculation implementations fail explicitly with SERVICE_UNAVAILABLE and no result payload.

`loanrun.reports.worker-enabled=false` disables automatic dispatch for admission-only tests/development. The
calculation adapter and stranded-job recovery remain separate changes; this worker is not a placeholder adapter
and does not itself call OpenAI or calculate loan schedules. Report #45 is not fully integrated until those remaining
parts are wired and tested. Source contracts/V5 remain pending, so this prepared branch is not a cumulative PR.
