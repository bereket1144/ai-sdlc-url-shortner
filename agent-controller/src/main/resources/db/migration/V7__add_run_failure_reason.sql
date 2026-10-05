-- Existing FAILED runs predate failure codes. Give them the safe fixed fallback code.
ALTER TABLE runs ADD COLUMN failure_reason VARCHAR(32);
UPDATE runs SET failure_reason = 'UNEXPECTED' WHERE status = 'FAILED';
ALTER TABLE runs ADD CONSTRAINT runs_failure_reason_known CHECK (
    failure_reason IS NULL OR failure_reason IN (
        'OUTPUT_INVALID', 'MODEL_UNAVAILABLE', 'CONTEXT_TOO_LARGE', 'BUDGET_EXHAUSTED',
        'REPLAY_MISSING', 'ACCESS_DENIED', 'REJECTED', 'ASSEMBLY_CONFLICT',
        'STOPPED_BY_REVIEWER', 'STAGE_TIMEOUT', 'RECOVERY_FAILED', 'UNEXPECTED'
    )
);
ALTER TABLE runs ADD CONSTRAINT runs_failure_reason_matches_status CHECK (
    (status = 'FAILED' AND failure_reason IS NOT NULL)
    OR (status <> 'FAILED' AND failure_reason IS NULL)
);
