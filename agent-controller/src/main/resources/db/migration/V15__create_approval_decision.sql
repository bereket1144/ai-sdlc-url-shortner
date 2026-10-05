-- AGT-15: one row for every decision a reviewer makes about one version of a stage's artifact. Rows are only ever added.
-- The unique key allows one decision for each version, so a second decision for it is refused by the database too.
-- comment is the reviewer's text: required for REJECT, optional for APPROVE. decided_at is filled when the row is stored.
CREATE TABLE approval_decision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES runs (id),
    stage VARCHAR(64) NOT NULL,
    artifact_kind VARCHAR(255) NOT NULL,
    artifact_version INTEGER NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    approver VARCHAR(100) NOT NULL,
    comment TEXT,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT approval_decision_known CHECK (decision IN ('APPROVE', 'REJECT')),
    CONSTRAINT uq_approval_decision_run_artifact UNIQUE (run_id, artifact_kind, artifact_version)
);

CREATE INDEX idx_approval_decision_run_stage ON approval_decision (run_id, stage);
