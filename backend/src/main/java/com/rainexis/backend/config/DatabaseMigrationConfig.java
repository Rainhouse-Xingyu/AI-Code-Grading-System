package com.rainexis.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigrationConfig {
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationConfig.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigrationConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_assignment_class (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  assignment_id BIGINT NOT NULL,
                  class_name VARCHAR(50) NOT NULL,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE (assignment_id, class_name)
                )
                """);
        createRubricTemplateTables();
        widenAssignmentClassName();
        ensureUserColumns();
        ensureAssignmentRubricColumns();
        ensureAiTaskColumns();
        normalizeFallbackTokenUsage();
        removeLegacySuperAdmin();
        jdbcTemplate.update("""
                INSERT INTO t_assignment_class (assignment_id, class_name)
                SELECT a.id, a.class_name
                FROM t_assignment a
                WHERE a.class_name IS NOT NULL
                  AND a.class_name <> ''
                  AND NOT EXISTS (
                    SELECT 1
                    FROM t_assignment_class ac
                    WHERE ac.assignment_id = a.id
                      AND ac.class_name = a.class_name
                  )
                """);
    }

    private void ensureAssignmentRubricColumns() {
        addColumnIfMissing("t_assignment", "course_name", "VARCHAR(100)");
        addColumnIfMissing("t_assignment", "rubric_template_id", "BIGINT");
        addColumnIfMissing("t_assignment", "selected_rubric_item_ids", "LONGTEXT");
        addColumnIfMissing("t_assignment", "normalized_rubric_json", "LONGTEXT");
    }

    private void ensureAiTaskColumns() {
        addColumnIfMissing("t_ai_task", "batch_id", "VARCHAR(64)");
    }

    /** 旧版本曾把确定性兜底的字符估算写成 Token；兜底没有调用模型，真实用量应为 0。 */
    private void normalizeFallbackTokenUsage() {
        int updated = jdbcTemplate.update("""
                UPDATE t_ai_report
                SET token_usage = 0
                WHERE LOWER(COALESCE(model_name, '')) = 'fallback-local'
                  AND COALESCE(token_usage, 0) <> 0
                """);
        if (updated > 0) {
            log.info("Normalized token usage for {} deterministic fallback reports", updated);
        }
    }

    private void ensureUserColumns() {
        addColumnIfMissing("t_user", "id_card_encrypted", "VARCHAR(512)");
        addColumnIfMissing("t_user", "employee_no", "VARCHAR(50)");
        addColumnIfMissing("t_user", "college", "VARCHAR(100)");
        addColumnIfMissing("t_user", "teaching_course", "VARCHAR(200)");
        addColumnIfMissing("t_user", "teaching_class", "VARCHAR(500)");
    }

    private void removeLegacySuperAdmin() {
        int converted = jdbcTemplate.update("""
                UPDATE t_user
                SET role = 'admin'
                WHERE role = 'super_admin'
                  AND username <> 'superadmin'
                """);
        int deleted = jdbcTemplate.update("DELETE FROM t_user WHERE username = ?", "superadmin");
        if (converted > 0 || deleted > 0) {
            log.info("Removed legacy super-admin role: converted={}, deleted={}", converted, deleted);
        }
    }

    private void createRubricTemplateTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_rubric_template (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  template_name VARCHAR(100) NOT NULL,
                  description LONGTEXT,
                  enabled TINYINT DEFAULT 1,
                  created_by BIGINT,
                  updated_by BIGINT,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
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
                )
                """);
        jdbcTemplate.execute("""
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
                )
                """);
    }

    private void widenAssignmentClassName() {
        try {
            jdbcTemplate.execute("ALTER TABLE t_assignment MODIFY COLUMN class_name VARCHAR(500)");
        } catch (Exception mysqlEx) {
            try {
                jdbcTemplate.execute("ALTER TABLE t_assignment ALTER COLUMN class_name VARCHAR(500)");
            } catch (Exception h2Ex) {
                log.debug("Skip widening t_assignment.class_name: {}", h2Ex.getMessage());
            }
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = ?
                  AND column_name = ?
                """, Integer.class, table, column);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
