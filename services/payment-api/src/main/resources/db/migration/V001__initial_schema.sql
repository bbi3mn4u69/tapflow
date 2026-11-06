CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE CHECK (email = lower(email) AND email = btrim(email) AND length(email) >= 3),
    password_hash varchar(1024) NOT NULL CHECK (password_hash LIKE '$argon2id$%'),
    account_type varchar(20) NOT NULL CHECK (account_type IN ('CUSTOMER', 'MERCHANT', 'SYSTEM_ADMIN')),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE merchants (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    slug varchar(80) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE merchant_memberships (
    user_id uuid NOT NULL REFERENCES users(id),
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    role varchar(10) NOT NULL CHECK (role IN ('OWNER', 'OPERATOR')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, merchant_id)
);

CREATE TABLE terminals (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    label varchar(80) NOT NULL CHECK (length(btrim(label)) > 0),
    installation_id uuid NOT NULL UNIQUE,
    status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    paired_by_user_id uuid NOT NULL REFERENCES users(id),
    paired_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz,
    UNIQUE (id, merchant_id)
);

CREATE TABLE terminal_pairing_codes (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    code_hash bytea NOT NULL UNIQUE CHECK (octet_length(code_hash) = 32),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);

CREATE TABLE virtual_cards (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL REFERENCES users(id),
    label varchar(80) NOT NULL CHECK (length(btrim(label)) > 0),
    public_key_spki bytea NOT NULL UNIQUE CHECK (octet_length(public_key_spki) BETWEEN 1 AND 1024),
    key_algorithm varchar(30) NOT NULL DEFAULT 'P256_SHA256_ECDSA' CHECK (key_algorithm = 'P256_SHA256_ECDSA'),
    status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wallets (
    id uuid PRIMARY KEY,
    card_id uuid NOT NULL UNIQUE REFERENCES virtual_cards(id),
    currency char(3) NOT NULL DEFAULT 'AUD' CHECK (currency = 'AUD'),
    balance_minor bigint NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE payment_intents (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    terminal_id uuid NOT NULL,
    card_id uuid REFERENCES virtual_cards(id),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000),
    currency char(3) NOT NULL DEFAULT 'AUD' CHECK (currency = 'AUD'),
    challenge_nonce bytea NOT NULL CHECK (octet_length(challenge_nonce) = 16),
    expires_at timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'AWAITING_TAP'
        CHECK (status IN ('AWAITING_TAP', 'APPROVED', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    decision_code varchar(40) CHECK (decision_code IN ('APPROVED', 'CARD_BLOCKED', 'INSUFFICIENT_FUNDS')),
    proof_sha256 bytea CHECK (octet_length(proof_sha256) = 32),
    receipt_number varchar(80) UNIQUE,
    order_reference varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    authorised_at timestamptz,
    UNIQUE (id, merchant_id),
    FOREIGN KEY (terminal_id, merchant_id) REFERENCES terminals(id, merchant_id),
    CHECK (expires_at > created_at),
    CHECK (status <> 'APPROVED' OR (card_id IS NOT NULL AND proof_sha256 IS NOT NULL
        AND receipt_number IS NOT NULL AND authorised_at IS NOT NULL AND decision_code IS NOT NULL AND decision_code = 'APPROVED'))
);

CREATE TABLE refunds (
    id uuid PRIMARY KEY,
    payment_intent_id uuid NOT NULL,
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000),
    reason varchar(40) NOT NULL CHECK (reason IN ('CUSTOMER_REQUEST', 'DUPLICATE', 'OTHER')),
    created_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (payment_intent_id, merchant_id) REFERENCES payment_intents(id, merchant_id)
);

CREATE TABLE wallet_ledger (
    id uuid PRIMARY KEY,
    wallet_id uuid NOT NULL REFERENCES wallets(id),
    payment_intent_id uuid REFERENCES payment_intents(id),
    refund_id uuid REFERENCES refunds(id),
    entry_type varchar(20) NOT NULL CHECK (entry_type IN ('DEMO_FUNDING', 'DEBIT', 'CREDIT')),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    balance_after_minor bigint NOT NULL CHECK (balance_after_minor >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (
        (entry_type = 'DEMO_FUNDING' AND payment_intent_id IS NULL AND refund_id IS NULL)
        OR (entry_type = 'DEBIT' AND payment_intent_id IS NOT NULL AND refund_id IS NULL)
        OR (entry_type = 'CREDIT' AND refund_id IS NOT NULL)
    )
);

CREATE TABLE idempotency_records (
    id uuid PRIMARY KEY,
    scope varchar(512) NOT NULL CHECK (length(scope) > 0),
    idempotency_key uuid NOT NULL,
    request_sha256 bytea NOT NULL CHECK (octet_length(request_sha256) = 32),
    response_status smallint CHECK (response_status BETWEEN 100 AND 599),
    response_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    UNIQUE (scope, idempotency_key),
    CHECK ((response_status IS NULL) = (response_json IS NULL)),
    CHECK (expires_at >= created_at + interval '24 hours')
);

CREATE TABLE refresh_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    family_id uuid NOT NULL,
    client_type varchar(10) NOT NULL CHECK (client_type IN ('WEB', 'MOBILE')),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    rotated_from_id uuid UNIQUE REFERENCES refresh_sessions(id),
    CHECK (expires_at > created_at)
);

CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    actor_user_id uuid REFERENCES users(id),
    merchant_id uuid REFERENCES merchants(id),
    event_type varchar(80) NOT NULL CHECK (length(event_type) > 0),
    resource_type varchar(40) NOT NULL CHECK (length(resource_type) > 0),
    resource_id uuid,
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details_json) = 'object'),
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER wallets_updated_at BEFORE UPDATE ON wallets FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER intents_updated_at BEFORE UPDATE ON payment_intents FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE FUNCTION deny_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'History records are append only' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER immutable_ledger BEFORE UPDATE OR DELETE ON wallet_ledger FOR EACH ROW EXECUTE FUNCTION deny_history_mutation();
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION deny_history_mutation();
