-- Read-only psql audit. No credentials, personal rows or payloads are printed.
-- Optional: psql -v audit_schema=public -f inspect_flyway.sql
\set ON_ERROR_STOP on
\if :{?audit_schema}
\else
\set audit_schema public
\endif
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT current_database() AS database_name, :'audit_schema' AS audit_schema,
       current_setting('TimeZone') AS connection_timezone;
SELECT to_regclass(format('%I.flyway_schema_history', :'audit_schema')) IS NOT NULL AS has_history \gset
\if :has_history
SELECT installed_rank,version,script,checksum,success
FROM :"audit_schema".flyway_schema_history ORDER BY installed_rank;
\else
SELECT 'NO_FLYWAY_HISTORY: identify the DB/schema before migration' AS audit_note;
\endif
SELECT table_name FROM information_schema.tables
WHERE table_schema=:'audit_schema' AND table_name IN
('demo_users','loans','finance_profiles','monthly_budgets','monthly_allocations',
 'report_jobs','strategy_candidates','repayment_plan_versions','overall_strategy_versions') ORDER BY table_name;
SELECT to_regclass(format('%I.loans', :'audit_schema')) IS NOT NULL AS has_loans,
       to_regclass(format('%I.monthly_allocations', :'audit_schema')) IS NOT NULL AS has_allocations,
       to_regclass(format('%I.monthly_budgets', :'audit_schema')) IS NOT NULL AS has_budgets,
       to_regclass(format('%I.report_jobs', :'audit_schema')) IS NOT NULL AS has_reports,
       to_regclass(format('%I.strategy_candidates', :'audit_schema')) IS NOT NULL AS has_candidates \gset
\if :has_loans
SELECT count(*) AS loan_rows FROM :"audit_schema".loans;
\else
SELECT 'V2_ABSENT: legacy loan IDs cannot be verified against owned loans' AS audit_note;
\endif
\if :has_allocations
SELECT count(*) AS allocation_rows FROM :"audit_schema".monthly_allocations;
\if :has_loans
\if :has_budgets
SELECT count(*) FILTER (WHERE l.id IS NULL) AS missing_loan_references,
       count(*) FILTER (WHERE l.id IS NOT NULL AND l.user_id<>b.user_id) AS wrong_owner_references
FROM :"audit_schema".monthly_allocations a
JOIN :"audit_schema".monthly_budgets b ON b.id=a.budget_id
LEFT JOIN :"audit_schema".loans l ON l.id=a.loan_id;
\endif
\endif
\endif
\if :has_reports
SELECT count(*) AS report_rows,count(*) FILTER (WHERE loan_id IS NOT NULL) AS loan_scoped_report_rows
FROM :"audit_schema".report_jobs;
\if :has_loans
SELECT count(*) FILTER (WHERE l.id IS NULL) AS missing_report_loan_references,
       count(*) FILTER (WHERE l.id IS NOT NULL AND l.user_id<>r.user_id) AS wrong_report_owner_references
FROM :"audit_schema".report_jobs r LEFT JOIN :"audit_schema".loans l ON l.id=r.loan_id
WHERE r.loan_id IS NOT NULL;
\endif
\endif
\if :has_candidates
-- Payload IDs need protected owner-aware inspection; do not print financial payloads here.
SELECT count(*) AS candidate_rows,
       count(*) FILTER (WHERE allocations_payload NOT IN ('[]','{}','')) AS candidate_payloads_to_review
FROM :"audit_schema".strategy_candidates;
\endif
COMMIT;
