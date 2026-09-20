ALTER TABLE `t_ai_report`
    ADD COLUMN `llm_status` VARCHAR(20) DEFAULT NULL COMMENT '大模型评分状态' AFTER `total_score`,
    ADD COLUMN `keyword_status` VARCHAR(20) DEFAULT NULL COMMENT '关键字匹配评分状态' AFTER `llm_status`,
    ADD COLUMN `llm_score` DECIMAL(5,2) DEFAULT NULL COMMENT '大模型总分' AFTER `keyword_status`,
    ADD COLUMN `keyword_score` DECIMAL(5,2) DEFAULT NULL COMMENT '关键字匹配总分' AFTER `llm_score`,
    ADD COLUMN `average_score` DECIMAL(5,2) DEFAULT NULL COMMENT '两种评分平均总分' AFTER `keyword_score`,
    ADD COLUMN `llm_result_json` LONGTEXT DEFAULT NULL COMMENT '大模型评分完整结果' AFTER `average_score`,
    ADD COLUMN `keyword_result_json` LONGTEXT DEFAULT NULL COMMENT '关键字匹配评分完整结果' AFTER `llm_result_json`;
