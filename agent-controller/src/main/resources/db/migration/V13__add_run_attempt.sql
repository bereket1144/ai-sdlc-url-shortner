-- AGT-66: a failed run can be queued again. attempt counts how many times the run has been queued; it is 1 for a run
-- that was never retried, so every older row reads as its first attempt. resumed_from says where the latest attempt
-- starts: a stage name, or START for the beginning. It stays empty until the run is retried.
ALTER TABLE runs ADD COLUMN attempt INTEGER NOT NULL DEFAULT 1;
ALTER TABLE runs ADD COLUMN resumed_from VARCHAR(32);
