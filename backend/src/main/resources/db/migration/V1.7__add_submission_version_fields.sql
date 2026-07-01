ALTER TABLE `t_submission`
    ADD COLUMN `submission_version` INT DEFAULT 1 COMMENT '同一学生同一作业的提交版本号' AFTER `file_name`,
    ADD COLUMN `is_current` TINYINT DEFAULT 1 COMMENT '是否为当前有效提交' AFTER `submission_version`,
    ADD INDEX `idx_submission_current` (`assignment_id`, `student_id`, `is_current`);
