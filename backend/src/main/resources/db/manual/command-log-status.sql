ALTER TABLE command_log ADD COLUMN IF NOT EXISTS status VARCHAR(20);

UPDATE command_log
SET status = CASE
    WHEN is_executed = TRUE AND (error_message IS NULL OR TRIM(error_message) = '') THEN 'SUCCEEDED'
    WHEN is_executed = FALSE AND error_message IS NOT NULL AND TRIM(error_message) <> '' THEN 'FAILED'
    ELSE 'UNKNOWN'
END
WHERE status IS NULL;

SELECT COUNT(*) AS unmigrated_count FROM command_log WHERE status IS NULL;
SELECT COUNT(*) AS inconsistent_legacy_count FROM command_log
WHERE (status = 'SUCCEEDED' AND (is_executed <> TRUE OR (error_message IS NOT NULL AND TRIM(error_message) <> '')))
   OR (status = 'FAILED' AND (is_executed <> FALSE OR error_message IS NULL OR TRIM(error_message) = ''));
SELECT status, COUNT(*) AS row_count FROM command_log GROUP BY status ORDER BY status;

ALTER TABLE command_log ALTER COLUMN status VARCHAR(20) NOT NULL;
ALTER TABLE command_log ALTER COLUMN status SET DEFAULT 'UNKNOWN';
