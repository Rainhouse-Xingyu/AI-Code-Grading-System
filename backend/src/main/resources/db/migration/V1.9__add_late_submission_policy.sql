ALTER TABLE t_assignment
    ADD COLUMN late_policy VARCHAR(20) DEFAULT 'forbid' AFTER end_time,
    ADD COLUMN late_penalty_percent INT DEFAULT 0 AFTER late_policy;

ALTER TABLE t_submission
    ADD COLUMN is_late TINYINT DEFAULT 0 AFTER upload_time;
