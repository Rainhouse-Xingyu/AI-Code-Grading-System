CREATE TABLE IF NOT EXISTS t_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL,
  real_name VARCHAR(50),
  email VARCHAR(100),
  phone VARCHAR(30),
  class_name VARCHAR(500),
  need_password_change TINYINT DEFAULT 0,
  login_fail_count INT DEFAULT 0,
  locked_until TIMESTAMP,
  token_version INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_assignment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(100) NOT NULL,
  description LONGTEXT,
  teacher_id BIGINT NOT NULL,
  language VARCHAR(50),
  class_name VARCHAR(50),
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  late_policy VARCHAR(20) DEFAULT 'forbid',
  late_penalty_percent INT DEFAULT 0,
  rubric_template_id BIGINT,
  selected_rubric_item_ids LONGTEXT,
  normalized_rubric_json LONGTEXT,
  status VARCHAR(20) DEFAULT 'draft',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_assignment_class (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  assignment_id BIGINT NOT NULL,
  class_name VARCHAR(50) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_file (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  file_name VARCHAR(255),
  storage_name VARCHAR(255),
  file_url VARCHAR(500),
  file_type VARCHAR(50),
  file_size BIGINT,
  uploader_id BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_submission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  assignment_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  file_url VARCHAR(500) NOT NULL,
  file_name VARCHAR(255),
  submission_version INT DEFAULT 1,
  is_current TINYINT DEFAULT 1,
  project_structure_id BIGINT,
  language VARCHAR(50),
  file_count INT DEFAULT 0,
  upload_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  is_late TINYINT DEFAULT 0,
  status VARCHAR(20) DEFAULT 'uploaded',
  current_score DECIMAL(5,2),
  current_report_id BIGINT
);

CREATE TABLE IF NOT EXISTS t_project_structure (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  submission_id BIGINT NOT NULL,
  structure_json LONGTEXT,
  language VARCHAR(50),
  file_count INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_rubric (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  assignment_id BIGINT NOT NULL,
  rubric_type VARCHAR(20) DEFAULT 'manual',
  file_url VARCHAR(500),
  rubric_json LONGTEXT NOT NULL,
  parsed_json LONGTEXT,
  version INT DEFAULT 1,
  rubric_version INT DEFAULT 1,
  is_active TINYINT DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_rubric_template (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_name VARCHAR(100) NOT NULL,
  description LONGTEXT,
  enabled TINYINT DEFAULT 1,
  created_by BIGINT,
  updated_by BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_rubric_template_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_id BIGINT NOT NULL,
  dimension_order INT NOT NULL,
  dimension_name VARCHAR(100) NOT NULL,
  point_order INT NOT NULL,
  point_name VARCHAR(200) NOT NULL,
  point_score DECIMAL(8,2) NOT NULL,
  point_ratio DECIMAL(8,4),
  criteria LONGTEXT,
  enabled TINYINT DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_rubric_dimension_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rubric_id BIGINT,
  assignment_id BIGINT NOT NULL,
  dimension_order INT NOT NULL,
  dimension_name VARCHAR(100) NOT NULL,
  point_order INT NOT NULL,
  point_name VARCHAR(200) NOT NULL,
  point_score DECIMAL(8,2) NOT NULL,
  point_ratio DECIMAL(8,4),
  criteria LONGTEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_ai_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  submission_id BIGINT NOT NULL,
  assignment_id BIGINT NOT NULL,
  model_name VARCHAR(50),
  status VARCHAR(20) DEFAULT 'pending',
  prompt_tokens INT DEFAULT 0,
  completion_tokens INT DEFAULT 0,
  total_tokens INT DEFAULT 0,
  error_message LONGTEXT,
  retry_count INT DEFAULT 0,
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_ai_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  submission_id BIGINT NOT NULL,
  level VARCHAR(20) DEFAULT 'INFO',
  message LONGTEXT,
  model_name VARCHAR(50),
  duration_ms BIGINT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_ai_report (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  submission_id BIGINT NOT NULL,
  task_id BIGINT,
  model_name VARCHAR(50),
  total_score DECIMAL(5,2),
  score_json LONGTEXT,
  score_detail_json LONGTEXT,
  file_analysis_json LONGTEXT,
  token_usage INT DEFAULT 0,
  report_markdown LONGTEXT,
  suggestion LONGTEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_teacher_review (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  submission_id BIGINT NOT NULL,
  ai_report_id BIGINT,
  teacher_id BIGINT NOT NULL,
  final_score DECIMAL(5,2),
  final_comment LONGTEXT,
  modified_json LONGTEXT,
  modified_markdown LONGTEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_grade_publish (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  submission_id BIGINT NOT NULL,
  assignment_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  final_score DECIMAL(5,2),
  report_id BIGINT,
  is_published TINYINT DEFAULT 0,
  published_at TIMESTAMP
);
