-- Accounts and their append-only transaction ledger.

CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    owner_name VARCHAR(100)   NOT NULL,
    balance    NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL,
    version    BIGINT         NOT NULL,
    -- Last line of defence against overdrafts, independent of application code.
    CONSTRAINT accounts_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE account_transactions (
    id              UUID PRIMARY KEY,
    account_id      UUID           NOT NULL REFERENCES accounts (id),
    type            VARCHAR(30)    NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    balance_after   NUMERIC(19, 2) NOT NULL,
    description     VARCHAR(140),
    idempotency_key VARCHAR(64),
    created_at      TIMESTAMPTZ    NOT NULL,
    CONSTRAINT account_transactions_type_valid CHECK (type IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT account_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT account_transactions_balance_after_non_negative CHECK (balance_after >= 0),
    -- A retried request carrying the same key can never be posted twice.
    CONSTRAINT account_transactions_idempotency_key_unique UNIQUE (account_id, idempotency_key)
);

-- Serves both the transaction history (newest first) and today's withdrawal total (a range scan on created_at).
CREATE INDEX account_transactions_account_created_at_idx ON account_transactions (account_id, created_at);
