CREATE TABLE IF NOT EXISTS `t_ai_log` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_id`       BIGINT      NOT NULL COMMENT '关联 t_ai_task.id',
    `submission_id` BIGINT      NOT NULL COMMENT '关联 t_submission.id',
    `level`         VARCHAR(20) DEFAULT 'INFO' COMMENT 'INFO/WARN/ERROR',
    `message`       LONGTEXT    DEFAULT NULL COMMENT '执行日志内容',
    `model_name`    VARCHAR(50) DEFAULT NULL COMMENT '模型名称',
    `duration_ms`   BIGINT      DEFAULT 0 COMMENT '阶段耗时毫秒',
    `created_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_ai_log_task_id` (`task_id`),
    INDEX `idx_ai_log_submission_id` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI任务执行日志表';
