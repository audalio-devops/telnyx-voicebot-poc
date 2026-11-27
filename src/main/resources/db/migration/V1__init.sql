CREATE TABLE IF NOT EXISTS call_record (
    id                      UUID PRIMARY KEY,
    external_ref            VARCHAR(255),
    call_sid                VARCHAR(255) UNIQUE,
    call_control_id         VARCHAR(255),
    call_session_id         VARCHAR(255),
    conversation_id         VARCHAR(255),
    assistant_id            VARCHAR(255) NOT NULL,
    from_number             VARCHAR(32)  NOT NULL,
    to_number               VARCHAR(32)  NOT NULL,
    status                  VARCHAR(32)  NOT NULL,
    telnyx_status           VARCHAR(64),
    answered_by             VARCHAR(64),
    started_at              TIMESTAMP WITH TIME ZONE,
    ended_at                TIMESTAMP WITH TIME ZONE,
    duration_sec            INTEGER,
    price                   NUMERIC(18, 6),
    price_unit              VARCHAR(16),
    cost_total              NUMERIC(18, 6),
    cost_currency           VARCHAR(16),
    cost_telephony          NUMERIC(18, 6),
    cost_ai                 NUMERIC(18, 6),
    cost_status             VARCHAR(32),
    cost_breakdown          JSONB,
    recording_key           VARCHAR(512),
    recording_duration_sec  INTEGER,
    recording_channels      VARCHAR(32),
    recording_source_sid    VARCHAR(255),
    callback_url            VARCHAR(1024),
    dynamic_variables       JSONB,
    collection_attempts     INTEGER NOT NULL DEFAULT 0,
    next_collection_at      TIMESTAMP WITH TIME ZONE,
    last_error              TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_call_record_status ON call_record (status);
CREATE INDEX IF NOT EXISTS idx_call_record_external_ref ON call_record (external_ref);
CREATE INDEX IF NOT EXISTS idx_call_record_conversation_id ON call_record (conversation_id);
CREATE INDEX IF NOT EXISTS idx_call_record_next_collection ON call_record (status, next_collection_at);

CREATE TABLE IF NOT EXISTS transcript_line (
    id          UUID PRIMARY KEY,
    call_id     UUID NOT NULL REFERENCES call_record(id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL,
    role        VARCHAR(64) NOT NULL,
    text        TEXT NOT NULL,
    spoken_at   TIMESTAMP WITH TIME ZONE,
    raw         JSONB
);

CREATE INDEX IF NOT EXISTS idx_transcript_line_call_seq ON transcript_line (call_id, seq);

CREATE TABLE IF NOT EXISTS webhook_event (
    id           UUID PRIMARY KEY,
    source       VARCHAR(64)  NOT NULL,
    event_key    VARCHAR(255) NOT NULL UNIQUE,
    payload      JSONB NOT NULL,
    received_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS outbound_notification (
    id              UUID PRIMARY KEY,
    call_id         UUID NOT NULL REFERENCES call_record(id) ON DELETE CASCADE,
    target_url      VARCHAR(1024) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_outbound_notification_due ON outbound_notification (status, next_attempt_at);

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

