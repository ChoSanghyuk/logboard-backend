-- When the error log was terminated (by USER or AGENT). Exposed as ErrorLogDetail.resolvedAt.
ALTER TABLE error_logs ADD COLUMN resolved_at TIMESTAMPTZ;
-- Best available value for logs terminated before this column existed.
UPDATE error_logs SET resolved_at = updated_at WHERE status = 'TERMINATED';
