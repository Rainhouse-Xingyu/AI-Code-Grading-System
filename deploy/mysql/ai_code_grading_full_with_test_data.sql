-- AI Code Grading System full MySQL initialization script with test accounts.
-- Usage:
--   mysql -uroot -p < deploy/mysql/ai_code_grading_full_with_test_data.sql
--
-- Test passwords:
--   Admin: admin / 12345
--   Teachers: Pass12345
--   Students: Stu123456 (legacy demo rows do not include encrypted ID card values)

CREATE DATABASE IF NOT EXISTS `ai_code_grading`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE `ai_code_grading`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `t_grade_publish`;
DROP TABLE IF EXISTS `t_teacher_review`;
DROP TABLE IF EXISTS `t_ai_report`;
DROP TABLE IF EXISTS `t_ai_log`;
DROP TABLE IF EXISTS `t_ai_task`;
DROP TABLE IF EXISTS `t_rubric_dimension_item`;
DROP TABLE IF EXISTS `t_rubric_template_item`;
DROP TABLE IF EXISTS `t_rubric_template`;
DROP TABLE IF EXISTS `t_rubric`;
DROP TABLE IF EXISTS `t_project_structure`;
DROP TABLE IF EXISTS `t_submission`;
DROP TABLE IF EXISTS `t_file`;
DROP TABLE IF EXISTS `t_assignment_class`;
DROP TABLE IF EXISTS `t_assignment`;
DROP TABLE IF EXISTS `t_user`;

CREATE TABLE `t_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password` varchar(100) NOT NULL,
  `role` varchar(20) NOT NULL,
  `real_name` varchar(50) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `class_name` varchar(500) DEFAULT NULL,
  `id_card_encrypted` varchar(512) DEFAULT NULL,
  `employee_no` varchar(50) DEFAULT NULL,
  `college` varchar(100) DEFAULT NULL,
  `teaching_course` varchar(200) DEFAULT NULL,
  `teaching_class` varchar(500) DEFAULT NULL,
  `need_password_change` tinyint DEFAULT 0,
  `login_fail_count` int DEFAULT 0,
  `locked_until` datetime DEFAULT NULL,
  `token_version` int DEFAULT 0,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_assignment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(100) NOT NULL,
  `course_name` varchar(100) DEFAULT NULL,
  `description` longtext,
  `teacher_id` bigint NOT NULL,
  `language` varchar(50) DEFAULT NULL,
  `class_name` varchar(500) DEFAULT NULL,
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

CREATE TABLE `t_assignment_class` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assignment_id` bigint NOT NULL,
  `class_name` varchar(50) NOT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_assignment_class` (`assignment_id`,`class_name`),
  KEY `idx_assignment_class_name` (`class_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_file` (
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

CREATE TABLE `t_submission` (
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

CREATE TABLE `t_project_structure` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `structure_json` longtext,
  `language` varchar(50) DEFAULT NULL,
  `file_count` int DEFAULT 0,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_project_structure_submission` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_rubric` (
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

CREATE TABLE `t_rubric_template` (
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

CREATE TABLE `t_rubric_template_item` (
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

CREATE TABLE `t_rubric_dimension_item` (
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

CREATE TABLE `t_ai_task` (
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

CREATE TABLE `t_ai_log` (
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

CREATE TABLE `t_ai_report` (
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

CREATE TABLE `t_teacher_review` (
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

CREATE TABLE `t_grade_publish` (
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

-- Seed users.
-- Default admin uses password 12345.
-- Teachers all use password Pass12345.
-- Legacy demo students all use password Stu123456 and need_password_change=1.
INSERT INTO `t_user`
  (`id`, `username`, `password`, `role`, `real_name`, `email`, `phone`, `class_name`, `need_password_change`, `login_fail_count`, `token_version`)
VALUES
  (900, 'admin', '$2a$10$uL6.NfU18h7tij7fjBfdrOHo9UpksmkfdkotLObczLNJo3dPNomEq', 'admin', '系统管理员', 'admin@example.edu.cn', NULL, NULL, 0, 0, 0),
  (1, 't001', '$2a$10$xSYVE49Y4gN1mOz78.S5eu8/3PKukc8X0ENl4pknELDW9AqjPdbQW', 'teacher', '王老师', 't001@example.edu.cn', '13800000001', 'CS-1', 0, 0, 0),
  (2, 't002', '$2a$10$xSYVE49Y4gN1mOz78.S5eu8/3PKukc8X0ENl4pknELDW9AqjPdbQW', 'teacher', '李老师', 't002@example.edu.cn', '13800000002', 'CS-2', 0, 0, 0),
  (3, 't003', '$2a$10$xSYVE49Y4gN1mOz78.S5eu8/3PKukc8X0ENl4pknELDW9AqjPdbQW', 'teacher', '赵老师', 't003@example.edu.cn', '13800000003', 'AI-1', 0, 0, 0),
  (101, 's001', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '张三', 's001@example.edu.cn', '13900000101', 'CS-1', 1, 0, 0),
  (102, 's002', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '李四', 's002@example.edu.cn', '13900000102', 'CS-1', 1, 0, 0),
  (103, 's003', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '王五', 's003@example.edu.cn', '13900000103', 'CS-1', 1, 0, 0),
  (104, 's004', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '赵六', 's004@example.edu.cn', '13900000104', 'CS-1', 1, 0, 0),
  (105, 's005', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '钱七', 's005@example.edu.cn', '13900000105', 'CS-1', 1, 0, 0),
  (201, 's101', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '孙一', 's101@example.edu.cn', '13900000201', 'CS-2', 1, 0, 0),
  (202, 's102', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '周二', 's102@example.edu.cn', '13900000202', 'CS-2', 1, 0, 0),
  (203, 's103', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '吴三', 's103@example.edu.cn', '13900000203', 'CS-2', 1, 0, 0),
  (204, 's104', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '郑四', 's104@example.edu.cn', '13900000204', 'CS-2', 1, 0, 0),
  (205, 's105', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '冯五', 's105@example.edu.cn', '13900000205', 'CS-2', 1, 0, 0),
  (301, 'a001', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '陈一', 'a001@example.edu.cn', '13900000301', 'AI-1', 1, 0, 0),
  (302, 'a002', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '褚二', 'a002@example.edu.cn', '13900000302', 'AI-1', 1, 0, 0),
  (303, 'a003', '$2a$10$4d9Fsu3t2gYDgDkbBIal8uOXoiTSwAz9p1lkMif1su3UYRYRz6mJ.', 'student', '卫三', 'a003@example.edu.cn', '13900000303', 'AI-1', 1, 0, 0);

INSERT INTO `t_assignment`
  (`id`, `title`, `course_name`, `description`, `teacher_id`, `language`, `class_name`, `start_time`, `end_time`, `late_policy`, `late_penalty_percent`, `status`)
VALUES
  (1, 'Java OOP Homework', 'Java程序设计', '请上传 zip 格式代码包，入口类需可运行。', 1, 'java', 'CS-1', NOW(), DATE_ADD(NOW(), INTERVAL 14 DAY), 'forbid', 0, 'published'),
  (2, 'Python Data Homework', 'Python数据处理', '完成数据处理脚本并打包提交。', 2, 'python', 'CS-2', NOW(), DATE_ADD(NOW(), INTERVAL 10 DAY), 'allow_mark', 0, 'published'),
  (3, 'AI Algorithm Homework', '人工智能算法', '实现一个简单搜索或分类算法。', 3, 'python', 'AI-1', NOW(), DATE_ADD(NOW(), INTERVAL 21 DAY), 'allow_penalty', 10, 'published'),
  (4, 'CS-1 Draft Assignment', 'Java程序设计', '草稿作业，学生暂不可见。', 1, 'java', 'CS-1', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 'forbid', 0, 'draft');

INSERT INTO `t_assignment_class`
  (`assignment_id`, `class_name`)
VALUES
  (1, 'CS-1'),
  (2, 'CS-2'),
  (3, 'AI-1'),
  (4, 'CS-1');

INSERT INTO `t_rubric`
  (`id`, `assignment_id`, `rubric_type`, `file_url`, `rubric_json`, `parsed_json`, `version`, `rubric_version`, `is_active`)
VALUES
  (1, 1, 'seed', NULL,
   '{"rubric_name":"Java OOP Homework Rubric","total_score":100,"dimensions":[{"name":"功能完整性","weight":40,"max_score":40,"criteria":"主要功能正确运行","items":[{"name":"核心功能","max_score":40,"criteria":"入口类可运行，输出符合要求"}]},{"name":"代码规范","weight":30,"max_score":30,"criteria":"命名、缩进、注释规范","items":[{"name":"规范性","max_score":30,"criteria":"命名清晰，缩进统一"}]},{"name":"结构设计","weight":30,"max_score":30,"criteria":"类与方法职责清晰","items":[{"name":"可维护性","max_score":30,"criteria":"结构清晰，可扩展"}]}]}',
   '{"rubric_name":"Java OOP Homework Rubric","total_score":100,"dimensions":[{"name":"功能完整性","weight":40,"max_score":40,"criteria":"主要功能正确运行","items":[{"name":"核心功能","max_score":40,"criteria":"入口类可运行，输出符合要求"}]},{"name":"代码规范","weight":30,"max_score":30,"criteria":"命名、缩进、注释规范","items":[{"name":"规范性","max_score":30,"criteria":"命名清晰，缩进统一"}]},{"name":"结构设计","weight":30,"max_score":30,"criteria":"类与方法职责清晰","items":[{"name":"可维护性","max_score":30,"criteria":"结构清晰，可扩展"}]}]}',
   1, 1, 1),
  (2, 2, 'seed', NULL,
   '{"rubric_name":"Python Data Homework Rubric","total_score":100,"dimensions":[{"name":"数据处理正确性","weight":50,"max_score":50,"criteria":"输入输出和边界情况正确","items":[{"name":"正确性","max_score":50,"criteria":"处理结果正确"}]},{"name":"代码质量","weight":30,"max_score":30,"criteria":"函数拆分合理，变量命名清晰","items":[{"name":"质量","max_score":30,"criteria":"可读性好"}]},{"name":"异常处理","weight":20,"max_score":20,"criteria":"处理缺失值、空文件等异常","items":[{"name":"健壮性","max_score":20,"criteria":"异常处理完善"}]}]}',
   '{"rubric_name":"Python Data Homework Rubric","total_score":100,"dimensions":[{"name":"数据处理正确性","weight":50,"max_score":50,"criteria":"输入输出和边界情况正确","items":[{"name":"正确性","max_score":50,"criteria":"处理结果正确"}]},{"name":"代码质量","weight":30,"max_score":30,"criteria":"函数拆分合理，变量命名清晰","items":[{"name":"质量","max_score":30,"criteria":"可读性好"}]},{"name":"异常处理","weight":20,"max_score":20,"criteria":"处理缺失值、空文件等异常","items":[{"name":"健壮性","max_score":20,"criteria":"异常处理完善"}]}]}',
   1, 1, 1),
  (3, 3, 'seed', NULL,
   '{"rubric_name":"AI Algorithm Homework Rubric","total_score":100,"dimensions":[{"name":"算法正确性","weight":50,"max_score":50,"criteria":"算法结果正确","items":[{"name":"正确性","max_score":50,"criteria":"核心算法能正确工作"}]},{"name":"复杂度与效率","weight":25,"max_score":25,"criteria":"时间和空间复杂度合理","items":[{"name":"效率","max_score":25,"criteria":"能处理常规规模输入"}]},{"name":"实验说明","weight":25,"max_score":25,"criteria":"代码注释和说明充分","items":[{"name":"说明","max_score":25,"criteria":"报告清楚说明实现思路"}]}]}',
  '{"rubric_name":"AI Algorithm Homework Rubric","total_score":100,"dimensions":[{"name":"算法正确性","weight":50,"max_score":50,"criteria":"算法结果正确","items":[{"name":"正确性","max_score":50,"criteria":"核心算法能正确工作"}]},{"name":"复杂度与效率","weight":25,"max_score":25,"criteria":"时间和空间复杂度合理","items":[{"name":"效率","max_score":25,"criteria":"能处理常规规模输入"}]},{"name":"实验说明","weight":25,"max_score":25,"criteria":"代码注释和说明充分","items":[{"name":"说明","max_score":25,"criteria":"报告清楚说明实现思路"}]}]}',
  1, 1, 1);

INSERT INTO `t_rubric_dimension_item`
  (`rubric_id`, `assignment_id`, `dimension_order`, `dimension_name`, `point_order`, `point_name`, `point_score`, `point_ratio`, `criteria`)
VALUES
  (1, 1, 1, '功能完整性', 1, '核心功能', 40, 40.0000, '入口类可运行，输出符合要求'),
  (1, 1, 2, '代码规范', 1, '规范性', 30, 30.0000, '命名清晰，缩进统一'),
  (1, 1, 3, '结构设计', 1, '可维护性', 30, 30.0000, '结构清晰，可扩展'),
  (2, 2, 1, '数据处理正确性', 1, '正确性', 50, 50.0000, '处理结果正确'),
  (2, 2, 2, '代码质量', 1, '质量', 30, 30.0000, '可读性好'),
  (2, 2, 3, '异常处理', 1, '健壮性', 20, 20.0000, '异常处理完善'),
  (3, 3, 1, '算法正确性', 1, '正确性', 50, 50.0000, '核心算法能正确工作'),
  (3, 3, 2, '复杂度与效率', 1, '效率', 25, 25.0000, '能处理常规规模输入'),
  (3, 3, 3, '实验说明', 1, '说明', 25, 25.0000, '报告清楚说明实现思路');

ALTER TABLE `t_user` AUTO_INCREMENT = 1000;
ALTER TABLE `t_assignment` AUTO_INCREMENT = 100;
ALTER TABLE `t_rubric` AUTO_INCREMENT = 100;

SET FOREIGN_KEY_CHECKS = 1;
