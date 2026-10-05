-- AGT-62: the result of one build and test of a run's committed workspace. Rows are only ever added; the latest row
-- of a run (highest id) is the one shown. failing_tests holds one test name per line (empty for none). excerpt is the
-- bounded start of the raw report. recorded_at is filled by the database; the application does not set or read it.
CREATE TABLE verification_result (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES runs (id),
    commit_id VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    tests_run INT NOT NULL,
    tests_passed INT NOT NULL,
    tests_failed INT NOT NULL,
    failing_tests TEXT NOT NULL,
    excerpt TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_verification_result_run_id_id ON verification_result (run_id, id);

-- AGT-62 adds the failure reason VERIFICATION_FAILED to the fixed list of AGT-9.
ALTER TABLE runs DROP CONSTRAINT runs_failure_reason_known;
ALTER TABLE runs ADD CONSTRAINT runs_failure_reason_known CHECK (
    failure_reason IS NULL OR failure_reason IN (
        'OUTPUT_INVALID', 'MODEL_UNAVAILABLE', 'CONTEXT_TOO_LARGE', 'BUDGET_EXHAUSTED',
        'REPLAY_MISSING', 'ACCESS_DENIED', 'REJECTED', 'ASSEMBLY_CONFLICT',
        'STOPPED_BY_REVIEWER', 'STAGE_TIMEOUT', 'RECOVERY_FAILED', 'UNEXPECTED', 'VERIFICATION_FAILED'
    )
);
