-- Scheduled payments. Funds are held from the source account when a payment executes, until the target account
-- accepts or the hold expires and the funds are returned.

CREATE TABLE scheduled_payments (
    id                UUID PRIMARY KEY,
    source_account_id UUID           NOT NULL REFERENCES accounts (id),
    target_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount            NUMERIC(19, 2) NOT NULL,
    description       VARCHAR(140),
    status            VARCHAR(30)    NOT NULL,
    execute_at        TIMESTAMPTZ    NOT NULL,
    held_at           TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    failure_reason    VARCHAR(255),
    idempotency_key   VARCHAR(64),
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    version           BIGINT         NOT NULL,
    CONSTRAINT scheduled_payments_status_valid
        CHECK (status IN ('SCHEDULED', 'PENDING_ACCEPTANCE', 'COMPLETED', 'EXPIRED', 'CANCELLED', 'FAILED')),
    CONSTRAINT scheduled_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT scheduled_payments_distinct_accounts CHECK (source_account_id <> target_account_id),
    -- Every payment that ever held funds records when, and when the hold expires.
    CONSTRAINT scheduled_payments_hold_recorded
        CHECK (status IN ('SCHEDULED', 'CANCELLED', 'FAILED') OR (held_at IS NOT NULL AND expires_at IS NOT NULL)),
    CONSTRAINT scheduled_payments_idempotency_key_unique UNIQUE (source_account_id, idempotency_key)
);

-- Partial indexes keep the scheduler's claim queries cheap however many completed payments accumulate.
CREATE INDEX scheduled_payments_due_idx ON scheduled_payments (execute_at) WHERE status = 'SCHEDULED';
CREATE INDEX scheduled_payments_overdue_idx ON scheduled_payments (expires_at) WHERE status = 'PENDING_ACCEPTANCE';
CREATE INDEX scheduled_payments_target_account_idx ON scheduled_payments (target_account_id);

-- Ledger entries created by payments reference them.
ALTER TABLE account_transactions
    ADD COLUMN payment_id UUID REFERENCES scheduled_payments (id),
    DROP CONSTRAINT account_transactions_type_valid,
    ADD CONSTRAINT account_transactions_type_valid
        CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'PAYMENT_HOLD', 'PAYMENT_REFUND', 'PAYMENT_RECEIVED')),
    ADD CONSTRAINT account_transactions_payment_reference
        CHECK ((type IN ('DEPOSIT', 'WITHDRAWAL')) = (payment_id IS NULL));
