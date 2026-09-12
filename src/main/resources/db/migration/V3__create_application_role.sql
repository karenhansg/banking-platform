-- Least-privilege role for the running application. In RDS it signs in with IAM auth tokens (rds_iam), so it has no
-- password. Schema changes are made only by the migration task, which connects as the master user.
-- Every future migration that creates a table must grant banking_app the privileges it needs.

DO
$$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'banking_app') THEN
        CREATE ROLE banking_app LOGIN;
    END IF;
    -- rds_iam only exists on Amazon RDS; local and test databases skip it.
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'rds_iam') THEN
        GRANT rds_iam TO banking_app;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO banking_app;
GRANT SELECT, INSERT, UPDATE ON accounts, scheduled_payments TO banking_app;
-- The ledger is append-only for the application: no UPDATE and no DELETE.
GRANT SELECT, INSERT ON account_transactions TO banking_app;
