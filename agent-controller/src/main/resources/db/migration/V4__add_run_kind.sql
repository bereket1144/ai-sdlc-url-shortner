-- Existing runs were submitted before callers could select a workflow kind.
ALTER TABLE runs ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'GREENFIELD';
ALTER TABLE runs ALTER COLUMN kind DROP DEFAULT;
