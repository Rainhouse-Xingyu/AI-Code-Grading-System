ALTER TABLE t_ai_task ADD COLUMN batch_id VARCHAR(64);
CREATE INDEX idx_ai_task_batch ON t_ai_task(batch_id);
