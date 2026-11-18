# Dashboard report badge policy

This pure policy handles only the report portion of API #42. It does not expose the endpoint, read sources or
generate reports. The future consumer must supply server plan/revision and an owner-scoped report snapshot.
FREE uses the locked NOT_GENERATED presentation with identifier, success date and N hidden; it does not
reveal whether a previously generated PRO report exists. PRO preserves actual persisted status and last-success
time. Foreign snapshots fail 404 rather than exposing another session's metadata.

N requires COMPLETED, the current revision and KST basis month, and age in [0,24h). Future timestamps,
exactly 24h, incomplete states and stale snapshots never display N. Unknown success time stays null.
Controller/source/risk composition and the pending DashboardQueryPort agreement remain separate work.
