-- Short-lived, browser-bound OAuth attempts, consumed before external code exchange.
CREATE TABLE oauth_attempt (
    id UUID PRIMARY KEY,
    state_hash TEXT NOT NULL UNIQUE,
    cookie_hash TEXT NOT NULL,
    nonce TEXT NOT NULL,
    code_verifier TEXT NOT NULL,
    return_to TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX oauth_attempt_expiry ON oauth_attempt (expires_at);
