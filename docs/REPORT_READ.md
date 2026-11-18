# Stored report reads

Latest and detail GETs check current PRO and session ownership. They create no job, run no worker/AI and do not
change financial revision. Optional latest `loan_id` is validated through the owner-scoped Team 1 query port;
detail ownership comes from the stored job, retaining old report access after source-loan changes.

No stored job means NOT_GENERATED metadata. Non-completed latest jobs have `report=null`; detail exposes
PENDING/RUNNING/FAILED state with null financial results and a fixed failure code/message where applicable.
Completed payloads are read from storage; missing/corrupt completed payloads fail instead of returning invented
data. Current revision/month determines stale on every read. Stale latest content is hidden, while explicit detail
remains addressable with `stale=true` and strategy disabled. Current withdrawn comparison consent hides comparison
in any old report without deleting the preserved personal result. `updated_at` is the returned report's last success
time, not a PENDING/RUNNING status-update time.

The detailed source schemas disagree on monthly principal rate type: #46 latest declares a string, #47 detail a
number. Endpoint adapters preserve those respective types (null remains null) without rewriting storage. The
frontend handoff records this difference; a unified type requires a separate team decision. V5/store/comparison
prerequisites must be integrated before opening this feature's central PR.
