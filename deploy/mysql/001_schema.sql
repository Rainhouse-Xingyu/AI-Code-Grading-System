SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS `t_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password` varchar(100) NOT NULL,
  `role` varchar(20) NOT NULL,
  `real_name` varchar(50) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `class_name` varchar(500) DEFAULT NULL,
  `need_password_change` tinyint DEFAULT 0,
  `login_fail_count` int DEFAULT 0,
  `locked_until` datetime DEFAULT NULL,
  `token_version` int DEFAULT 0,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_assignment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(100) NOT NULL,
  `description` longtext,
  `teacher_id` bigint NOT NULL,
  `language` varchar(50) DEFAULT NULL,
  `class_name` varchar(50) DEFAULT NULL,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `late_policy` varchar(20) DEFAULT 'forbid',
  `late_penalty_percent` int DEFAULT 0,
  `rubric_template_id` bigint DEFAULT NULL,
  `selected_rubric_item_ids` longtext,
  `normalized_rubric_json` longtext,
  `status` varchar(20) DEFAULT 'draft',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_assignment_teacher` (`teacher_id`),
  KEY `idx_assignment_class` (`class_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_assignment_class` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assignment_id` bigint NOT NULL,
  `class_name` varchar(50) NOT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_assignment_class` (`assignment_id`,`class_name`),
  KEY `idx_assignment_class_name` (`class_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) DEFAULT NULL,
  `storage_name` varchar(255) DEFAULT NULL,
  `file_url` varchar(500) DEFAULT NULL,
  `file_type` varchar(50) DEFAULT NULL,
  `file_size` bigint DEFAULT NULL,
  `uploader_id` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_file_uploader` (`uploader_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_submission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assignment_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `file_url` varchar(500) NOT NULL,
  `file_name` varchar(255) DEFAULT NULL,
  `submission_version` int DEFAULT 1,
  `is_current` tinyint DEFAULT 1,
  `project_structure_id` bigint DEFAULT NULL,
  `language` varchar(50) DEFAULT NULL,
  `file_count` int DEFAULT 0,
  `upload_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `is_late` tinyint DEFAULT 0,
  `status` varchar(20) DEFAULT 'uploaded',
  `current_score` decimal(5,2) DEFAULT NULL,
  `current_report_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_submission_assignment` (`assignment_id`),
  KEY `idx_submission_student` (`student_id`),
  KEY `idx_submission_current` (`assignment_id`,`student_id`,`is_current`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_project_structure` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `structure_json` longtext,
  `language` varchar(50) DEFAULT NULL,
  `file_count` int DEFAULT 0,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_project_structure_submission` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_rubric` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assignment_id` bigint NOT NULL,
  `rubric_type` varchar(20) DEFAULT 'manual',
  `file_url` varchar(500) DEFAULT NULL,
  `rubric_json` longtext NOT NULL,
  `parsed_json` longtext,
  `version` int DEFAULT 1,
  `rubric_version` int DEFAULT 1,
  `is_active` tinyint DEFAULT 1,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rubric_assignment` (`assignment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_rubric_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_name` varchar(100) NOT NULL,
  `description` longtext,
  `enabled` tinyint DEFAULT 1,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_rubric_template_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_id` bigint NOT NULL,
  `dimension_order` int NOT NULL,
  `dimension_name` varchar(100) NOT NULL,
  `point_order` int NOT NULL,
  `point_name` varchar(200) NOT NULL,
  `point_score` decimal(8,2) NOT NULL,
  `point_ratio` decimal(8,4) DEFAULT NULL,
  `criteria` longtext,
  `enabled` tinyint DEFAULT 1,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rubric_template_item_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_rubric_dimension_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rubric_id` bigint DEFAULT NULL,
  `assignment_id` bigint NOT NULL,
  `dimension_order` int NOT NULL,
  `dimension_name` varchar(100) NOT NULL,
  `point_order` int NOT NULL,
  `point_name` varchar(200) NOT NULL,
  `point_score` decimal(8,2) NOT NULL,
  `point_ratio` decimal(8,4) DEFAULT NULL,
  `criteria` longtext,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rubric_dimension_item_rubric` (`rubric_id`),
  KEY `idx_rubric_dimension_item_assignment` (`assignment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_ai_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `assignment_id` bigint NOT NULL,
  `batch_id` varchar(64) DEFAULT NULL,
  `model_name` varchar(50) DEFAULT NULL,
  `status` varchar(20) DEFAULT 'pending',
  `prompt_tokens` int DEFAULT 0,
  `completion_tokens` int DEFAULT 0,
  `total_tokens` int DEFAULT 0,
  `error_message` longtext,
  `retry_count` int DEFAULT 0,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_task_batch` (`batch_id`),
  KEY `idx_ai_task_assignment` (`assignment_id`),
  KEY `idx_ai_task_submission` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_ai_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `submission_id` bigint NOT NULL,
  `level` varchar(20) DEFAULT 'INFO',
  `message` longtext,
  `model_name` varchar(50) DEFAULT NULL,
  `duration_ms` bigint DEFAULT 0,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_log_task` (`task_id`),
  KEY `idx_ai_log_submission` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_ai_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `task_id` bigint DEFAULT NULL,
  `model_name` varchar(50) DEFAULT NULL,
  `total_score` decimal(5,2) DEFAULT NULL,
  `score_json` longtext,
  `score_detail_json` longtext,
  `file_analysis_json` longtext,
  `token_usage` int DEFAULT 0,
  `report_markdown` longtext,
  `suggestion` longtext,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_report_submission` (`submission_id`),
  KEY `idx_ai_report_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_teacher_review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `ai_report_id` bigint DEFAULT NULL,
  `teacher_id` bigint NOT NULL,
  `final_score` decimal(5,2) DEFAULT NULL,
  `final_comment` longtext,
  `modified_json` longtext,
  `modified_markdown` longtext,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_teacher_review_submission` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `t_grade_publish` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `assignment_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `final_score` decimal(5,2) DEFAULT NULL,
  `report_id` bigint DEFAULT NULL,
  `is_published` tinyint DEFAULT 0,
  `published_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_grade_publish_assignment` (`assignment_id`),
  KEY `idx_grade_publish_student` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
