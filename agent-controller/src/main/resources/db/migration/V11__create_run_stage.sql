-- AGT-62: the state of each SDLC stage of a run, so a client can see how far the run has got.
-- A stage with no row has not started. state is RUNNING or FINISHED; a stage that failed stays RUNNING.
CREATE TABLE run_stage (
    run_id UUID NOT NULL REFERENCES runs (id),
    stage VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, stage)
);
