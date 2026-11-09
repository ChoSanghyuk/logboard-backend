-- Keep the scan result with the error log, so GET and re-crop never re-scan the (possibly large) raw file.
ALTER TABLE error_logs
    ADD COLUMN anchor_end_line INTEGER NOT NULL,  -- last line of the anchor entry (its stack trace)
    ADD COLUMN total_lines     INTEGER NOT NULL,  -- line count of the raw file
    ADD COLUMN candidates      TEXT    NOT NULL;  -- JSON array of crop.Candidate in the search window
ALTER TABLE error_logs ADD CONSTRAINT error_logs_anchor_entry
    CHECK (anchor_line <= anchor_end_line AND anchor_end_line <= crop_end_line AND crop_end_line <= total_lines);
