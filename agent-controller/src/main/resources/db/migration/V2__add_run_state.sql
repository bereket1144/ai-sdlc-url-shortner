-- RUN-6: a run records its status, when it was created and last changed, and its version.
-- The defaults fill the rows that already exist (status CREATED, both times set to the moment of this
-- migration, version 0). They are dropped afterwards so that every new row must supply its own values.
ALTER TABLE runs
    ADD COLUMN status VARCHAR(255) NOT NULL DEFAULT 'CREATED',
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE runs
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN updated_at DROP DEFAULT,
    ALTER COLUMN version DROP DEFAULT;
