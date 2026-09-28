-- M1: GitHub webhook intake.
--
-- A row is written before the HTTP response is sent, so this table is both the
-- queue and the audit trail. X-GitHub-Delivery is the primary key: a redelivery
-- collides with the existing row instead of producing a second delivery.

CREATE TABLE webhook_delivery (
    delivery_id           uuid        PRIMARY KEY,
    event_type            text        NOT NULL,
    action                text,
    repository_id         bigint,
    payload               jsonb       NOT NULL,
    status                text        NOT NULL DEFAULT 'PENDING',
    attempts              integer     NOT NULL DEFAULT 0,
    error                 text,
    received_at           timestamptz NOT NULL DEFAULT now(),
    processing_started_at timestamptz,
    processed_at          timestamptz,

    CONSTRAINT webhook_delivery_status_ck
        CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'IGNORED', 'FAILED')),

    CONSTRAINT webhook_delivery_attempts_ck
        CHECK (attempts >= 0),

    -- processed_at is set exactly when the delivery reached a terminal state.
    -- Catches a half-finished update that moved status without the timestamp.
    CONSTRAINT webhook_delivery_processed_at_ck
        CHECK ((status IN ('PROCESSED', 'IGNORED', 'FAILED')) = (processed_at IS NOT NULL)),

    -- processing_started_at is set exactly while the row is claimed. A crash
    -- between claim and completion leaves PROCESSING plus a timestamp, which is
    -- what makes the row recoverable rather than stuck.
    CONSTRAINT webhook_delivery_processing_started_at_ck
        CHECK ((status = 'PROCESSING') = (processing_started_at IS NOT NULL))
);

-- The only query that scans by status is the sweeper. A partial index keeps it
-- small no matter how many deliveries have already reached a terminal state.
-- Both timestamps are indexed because the sweeper filters on either one.
CREATE INDEX webhook_delivery_inflight_idx
    ON webhook_delivery (received_at, processing_started_at)
    WHERE status IN ('PENDING', 'PROCESSING');
