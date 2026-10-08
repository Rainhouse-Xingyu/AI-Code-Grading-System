ALTER TABLE `t_ai_report`
    ADD COLUMN `ai_process_status` VARCHAR(20) DEFAULT NULL COMMENT 'AI过程性应用评分状态' AFTER `keyword_status`,
    ADD COLUMN `ai_process_score` DECIMAL(5,2) DEFAULT NULL COMMENT 'AI过程性应用得分' AFTER `keyword_score`,
    ADD COLUMN `ai_process_result_json` LONGTEXT DEFAULT NULL COMMENT 'AI过程性应用评分完整结果' AFTER `keyword_result_json`;
