/*
 Navicat Premium Dump SQL

 Source Server         : Alibaba_Ulanqab_Server
 Source Server Type    : MySQL
 Source Server Version : 80042 (8.0.42-0ubuntu0.20.04.1)
 Source Host           : 8.130.76.85:3306
 Source Schema         : neusoft_ai_code_grading_ststem

 Target Server Type    : MySQL
 Target Server Version : 80042 (8.0.42-0ubuntu0.20.04.1)
 File Encoding         : 65001

 Date: 25/06/2026 13:33:58
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for t_ai_log
-- ----------------------------
DROP TABLE IF EXISTS `t_ai_log`;
CREATE TABLE `t_ai_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint DEFAULT NULL,
  `request_text` longtext,
  `response_text` longtext,
  `model_name` varchar(50) DEFAULT NULL,
  `latency_ms` int DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for t_ai_report
-- ----------------------------
DROP TABLE IF EXISTS `t_ai_report`;
CREATE TABLE `t_ai_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `task_id` bigint DEFAULT NULL,
  `model_name` varchar(50) DEFAULT NULL COMMENT 'deepseek/fallback',
  `total_score` decimal(5,2) DEFAULT NULL,
  `score_json` json DEFAULT NULL COMMENT '分项评分(简化版)',
  `score_detail_json` longtext COMMENT '分项评分完整JSON(维度→得分→评语)',
  `file_analysis_json` longtext COMMENT '每个文件的分析结果JSON',
  `token_usage` int DEFAULT 0 COMMENT '本次评分消耗的token总数',
  `report_markdown` longtext COMMENT 'Markdown报告全文',
  `suggestion` text,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for t_ai_task
-- ----------------------------
DROP TABLE IF EXISTS `t_ai_task`;
CREATE TABLE `t_ai_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `assignment_id` bigint NOT NULL,
  `model_name` varchar(50) DEFAULT NULL COMMENT 'deepseek/qwen-coder-7b/local',
  `status` varchar(20) DEFAULT 'pending' COMMENT 'pending/running/success/failed',
  `prompt_tokens` int DEFAULT 0,
  `completion_tokens` int DEFAULT 0,
  `total_tokens` int DEFAULT 0,
  `error_message` text,
  `retry_count` int DEFAULT 0,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for t_assignment
-- ----------------------------
DROP TABLE IF EXISTS `t_assignment`;
CREATE TABLE `t_assignment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(100) NOT NULL COMMENT '作业标题',
  `description` text,
  `teacher_id` bigint NOT NULL,
  `language` varchar(50) DEFAULT NULL COMMENT '编程语言 java/python/c/cpp',
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL COMMENT '截止时间',
  `late_policy` varchar(20) DEFAULT 'forbid' COMMENT '迟交策略 forbid/allow_mark/allow_penalty',
  `late_penalty_percent` int DEFAULT 0 COMMENT '迟交扣分比例',
  `status` varchar(20) DEFAULT 'draft' COMMENT 'draft/published/closed',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for t_file
-- ----------------------------
DROP TABLE IF EXISTS `t_file`;
CREATE TABLE `t_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) DEFAULT NULL COMMENT '原始文件名',
  `storage_name` varchar(255) DEFAULT NULL COMMENT '重命名: {学号}_{姓名}.zip',
  `file_url` varchar(500) DEFAULT NULL COMMENT '存储路径',
  `file_type` varchar(50) DEFAULT NULL COMMENT 'submission_zip/rubric_word/rubric_excel',
  `file_size` bigint DEFAULT NULL,
  `uploader_id` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for t_grade_publish
-- ----------------------------
DROP TABLE IF EXISTS `t_grade_publish`;
CREATE TABLE `t_grade_publish` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `assignment_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `final_score` decimal(5,2) DEFAULT NULL,
  `report_id` bigint DEFAULT NULL COMMENT '关联t_teacher_review',
  `is_published` tinyint DEFAULT 0 COMMENT '0=未发布 1=已发布 2=已撤回',
  `published_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for t_project_structure
-- ----------------------------
DROP TABLE IF EXISTS `t_project_structure`;
CREATE TABLE `t_project_structure` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `structure_json` longtext COMMENT '完整代码结构JSON(file_tree+contents+structure_summary)',
  `language` varchar(50) DEFAULT NULL,
  `file_count` int DEFAULT 0,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_submission_id` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='代码结构存储表-ZIP解压后生成';

-- ----------------------------
-- Table structure for t_rubric
-- ----------------------------
DROP TABLE IF EXISTS `t_rubric`;
CREATE TABLE `t_rubric` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assignment_id` bigint NOT NULL,
  `rubric_type` varchar(20) DEFAULT 'manual' COMMENT 'manual/auto(WordExcel解析)',
  `file_url` varchar(500) DEFAULT NULL,
  `rubric_json` json NOT NULL COMMENT '结构化评分标准',
  `parsed_json` longtext COMMENT 'Word/Excel原始解析JSON(冗余存储)',
  `version` int DEFAULT 1,
  `rubric_version` int DEFAULT 1 COMMENT '评分标准版本号，每次修改递增',
  `is_active` tinyint DEFAULT 1,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for t_submission
-- ----------------------------
DROP TABLE IF EXISTS `t_submission`;
CREATE TABLE `t_submission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assignment_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `file_url` varchar(500) NOT NULL COMMENT 'ZIP存储路径',
  `file_name` varchar(255) DEFAULT NULL COMMENT '原始文件名',
  `project_structure_id` bigint DEFAULT NULL COMMENT '关联t_project_structure.id',
  `language` varchar(50) DEFAULT NULL COMMENT '编程语言',
  `file_count` int DEFAULT 0 COMMENT '代码文件数量',
  `upload_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `is_late` tinyint DEFAULT 0 COMMENT '是否迟交',
  `status` varchar(20) DEFAULT 'uploaded' COMMENT 'uploaded/parsed/scoring/scored/reviewed/published/parse_failed',
  `current_score` decimal(5,2) DEFAULT NULL,
  `current_report_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for t_teacher_review
-- ----------------------------
DROP TABLE IF EXISTS `t_teacher_review`;
CREATE TABLE `t_teacher_review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `ai_report_id` bigint DEFAULT NULL,
  `teacher_id` bigint NOT NULL,
  `final_score` decimal(5,2) DEFAULT NULL COMMENT '教师修改后总分',
  `final_comment` text,
  `modified_json` json DEFAULT NULL COMMENT '修改后的评分结构',
  `modified_markdown` longtext COMMENT '教师修改后的Markdown报告全文',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Table structure for t_user
-- ----------------------------
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL COMMENT '学号/工号',
  `password` varchar(100) NOT NULL COMMENT 'BCrypt加密',
  `role` varchar(20) NOT NULL COMMENT 'student/teacher/admin',
  `real_name` varchar(50) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `class_name` varchar(50) DEFAULT NULL COMMENT '班级',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
