-- AGT-43: a reviewer can ask a RUNNING run to stop. The graph thread reads this flag at its safe points and ends the
-- attempt as STOPPED; the flag is cleared when the run starts again or stops. The status column has no CHECK on its
-- values, so the new status STOPPED needs no change here. The default is kept so that a row written without the
-- column, such as one a test inserts by hand, reads as "no stop requested".
ALTER TABLE runs ADD COLUMN stop_requested BOOLEAN NOT NULL DEFAULT FALSE;
