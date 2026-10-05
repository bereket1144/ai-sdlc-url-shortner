-- Outstanding reservations count calls that started but have not yet produced a durable call record.
CREATE TABLE model_budget_reservation (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES runs (id),
    role VARCHAR(255) NOT NULL,
    estimated_prompt_tokens BIGINT NOT NULL CHECK (estimated_prompt_tokens >= 0),
    reserved_answer_tokens BIGINT NOT NULL CHECK (reserved_answer_tokens > 0)
);
CREATE INDEX idx_model_budget_reservation_run ON model_budget_reservation (run_id);
