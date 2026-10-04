CREATE INDEX idx_runs_status_created_at_id
    ON runs (status, created_at, id);
