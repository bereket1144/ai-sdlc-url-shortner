-- AGT-68: generated work is retained when it cannot safely be merged into the controller checkout.
ALTER TABLE runs DROP CONSTRAINT runs_failure_reason_known;
ALTER TABLE runs ADD CONSTRAINT runs_failure_reason_known CHECK (
    failure_reason IS NULL OR failure_reason IN (
        'OUTPUT_INVALID', 'MODEL_UNAVAILABLE', 'CONTEXT_TOO_LARGE', 'BUDGET_EXHAUSTED',
        'REPLAY_MISSING', 'ACCESS_DENIED', 'REJECTED', 'ASSEMBLY_CONFLICT',
        'STOPPED_BY_REVIEWER', 'STAGE_TIMEOUT', 'RECOVERY_FAILED', 'UNEXPECTED',
        'VERIFICATION_FAILED', 'DELIVERY_BLOCKED'
    )
);
