-- Items live in immutable requirements artifacts; replies are stored separately against that version.
CREATE TABLE clarification_batch (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    artifact_version INTEGER NOT NULL,
    answers TEXT,
    answered_at TIMESTAMPTZ,
    CONSTRAINT uq_clarification_run_version UNIQUE (run_id, artifact_version)
);
