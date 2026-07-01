-- ============================================
-- AI Code Grading System 数据库升级脚本
-- 版本: V1.1
-- 日期: 2026-06-25
-- 说明: 为 AI 评分链路新增字段 + 新建 project_structure 表
-- 注意: 所有变更向后兼容，不删除不修改已有字段
-- ============================================

-- ============================================
-- 1. t_submission（提交表）新增字段
-- ============================================
ALTER TABLE `t_submission`
    ADD COLUMN `project_structure_id` BIGINT       DEFAULT NULL COMMENT '关联t_project_structure.id，指向结构化代码JSON' AFTER `current_report_id`,
    ADD COLUMN `language`             VARCHAR(50)  DEFAULT NULL COMMENT '编程语言类型(java/python/c/cpp等)'    AFTER `project_structure_id`,
    ADD COLUMN `file_count`           INT          DEFAULT 0    COMMENT '代码文件数量'                         AFTER `language`;

-- ============================================
-- 2. t_ai_report（AI评分结果表）新增字段
-- ============================================
ALTER TABLE `t_ai_report`
    ADD COLUMN `score_detail_json`   LONGTEXT DEFAULT NULL COMMENT '分项评分JSON结构(维度→得分→评语)'         AFTER `score_json`,
    ADD COLUMN `file_analysis_json`  LONGTEXT DEFAULT NULL COMMENT '每个文件的分析结果JSON'                     AFTER `score_detail_json`,
    ADD COLUMN `token_usage`         INT      DEFAULT 0    COMMENT '本次评分消耗的token总数'                  AFTER `file_analysis_json`;

-- ============================================
-- 3. t_rubric（评分标准表）新增字段
-- 注意: DB 已有 version(INT) 字段，新增 rubric_version 区分语义
-- ============================================
ALTER TABLE `t_rubric`
    ADD COLUMN `rubric_type`    VARCHAR(20)  DEFAULT 'manual' COMMENT '评分标准类型(manual=手动录入/auto=WordExcel解析)' AFTER `assignment_id`,
    ADD COLUMN `rubric_version` INT          DEFAULT 1       COMMENT '评分标准版本号，每次修改递增'                    AFTER `version`,
    ADD COLUMN `parsed_json`    LONGTEXT     DEFAULT NULL    COMMENT 'Word/Excel解析后的完整JSON结构(冗余存储)'     AFTER `rubric_json`;

-- ============================================
-- 4. 新建 t_project_structure（代码结构表）
-- ============================================
DROP TABLE IF EXISTS `t_project_structure`;
CREATE TABLE `t_project_structure` (
    `id`              BIGINT    NOT NULL AUTO_INCREMENT COMMENT '主键',
    `submission_id`   BIGINT    NOT NULL               COMMENT '关联t_submission.id',
    `structure_json`  LONGTEXT  DEFAULT NULL           COMMENT '代码JSON结构(file_tree+contents)',
    `language`        VARCHAR(50) DEFAULT NULL         COMMENT '编程语言',
    `file_count`      INT       DEFAULT 0              COMMENT '文件数量',
    `created_at`      DATETIME  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_submission_id` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='代码结构存储表(SpringBoot解压ZIP后生成)';
