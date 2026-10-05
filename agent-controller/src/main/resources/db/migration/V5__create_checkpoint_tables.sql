-- AGT-34: the tables the LangGraph4j PostgreSQL saver (1.8.27) reads and writes. The saver can create them itself,
-- but CheckpointSaverFactory never asks it to, so this migration is the only thing that creates them.
-- Names, columns, the foreign key and the indexes are exactly what the saver creates and expects (the unique index
-- on the thread name is needed by its INSERT ... ON CONFLICT). Check the saver before changing any of them.
--
-- state_data holds {"binaryPayload": "<base64>"}. The base64 is the graph state as UTF-8 JSON text.
-- To read a stored state, run:
--   SELECT convert_from(decode(state_data->>'binaryPayload', 'base64'), 'UTF8') FROM LG4JCheckpoint

CREATE TABLE LG4JThread (
    thread_id UUID PRIMARY KEY,
    thread_name VARCHAR(255),
    is_released BOOLEAN DEFAULT FALSE NOT NULL
);

CREATE TABLE LG4JCheckpoint (
    checkpoint_id UUID PRIMARY KEY,
    parent_checkpoint_id UUID,
    thread_id UUID NOT NULL,
    node_id VARCHAR(255),
    next_node_id VARCHAR(255),
    state_data JSONB NOT NULL,
    state_content_type VARCHAR(100) NOT NULL,
    saved_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_thread
        FOREIGN KEY (thread_id)
        REFERENCES LG4JThread (thread_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_lg4jcheckpoint_thread_id ON LG4JCheckpoint (thread_id);
CREATE INDEX idx_lg4jcheckpoint_thread_id_saved_at_desc ON LG4JCheckpoint (thread_id, saved_at DESC);
CREATE UNIQUE INDEX idx_unique_lg4jthread_thread_name_unreleased ON LG4JThread (thread_name) WHERE is_released = FALSE;
