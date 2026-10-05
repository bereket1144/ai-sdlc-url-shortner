-- AGT-50: a BROWNFIELD run changes the result of an earlier run, which base_run_id names. It stays empty for a
-- GREENFIELD run and for every run stored before this story. A base run is never deleted while a run points at it.
ALTER TABLE runs ADD COLUMN base_run_id UUID REFERENCES runs (id);
